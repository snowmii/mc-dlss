#include "internal/motion.h"

#include <cstring>

namespace mc_dlss {
namespace {

constexpr uint32_t kMotionSpirV[] =
#include "mc_dlss_motion.spv.h"
    ;

constexpr uint32_t kVelocityFillSpirV[] =
#include "mc_dlss_velocity_fill.spv.h"
    ;

// The largest descriptor-set binding layout either dispatch pass declares. The camera-only
// pass binds four descriptors (depth, motion, flipped motion, probe), the velocity fill five
// (depth, velocity, motion, flipped motion, probe).
constexpr uint32_t kMaxDispatchBindings = 5;

// Push-constant block, matching mc_dlss_motion.comp exactly: a column-major mat4 followed by
// the render size and the probe ring slot.
struct DlssMotionPushConstants {
    float reprojection[16];
    int32_t renderWidth;
    int32_t renderHeight;
    int32_t probeSlot;
};

// Push-constant block, matching mc_dlss_velocity_fill.comp exactly: the same mat4 and render
// size, plus the reset flag and the probe ring slot.
struct DlssVelocityFillPushConstants {
    float reprojection[16];
    int32_t renderWidth;
    int32_t renderHeight;
    int32_t reset;
    int32_t probeSlot;
};

// The two passes this module records declare different descriptor layouts: the camera-only
// pass samples depth into a storage destination, and the fill samples depth and the engine's
// velocity companion into the same storage destination. Both also write the flipped motion
// copy the FG tag names, as a second storage image.
constexpr VkDescriptorType kMotionBindingTypes[] = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                                                    VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                                    VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                                    VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};
constexpr VkDescriptorType kVelocityFillBindingTypes[] = {
    VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
    VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
    VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};

// The velocity-fill pass lives here rather than in g_state because it is the velocity-MRT
// route's merge dispatch: it has the same shape as the motion pass - one shader, one
// pipeline, one ring of descriptor sets - but shares none of the motion pass's handles. It
// dies with the motion pass, in the same destroy. The companion view is the fill's third
// binding, which DlssMotionPass has no field for, so the pair rides together here.
struct VelocityFillPass {
    DlssMotionPass pass;
    uint64_t boundVelocityView = 0;
};

VelocityFillPass g_velocityFill;

constexpr uint32_t kMotionProbeRing = 3;
constexpr VkDeviceSize kMotionProbeBytes = kMotionProbeRing * 4 * sizeof(float);

struct MotionProbeBuffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    float* mapped = nullptr;
    uint32_t records = 0;
};

MotionProbeBuffer g_probe;

bool find_host_memory_type(const uint32_t typeBits, uint32_t* index) noexcept {
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    vkGetPhysicalDeviceMemoryProperties(g_state.physicalDevice, &memoryProperties);
    for (uint32_t candidate = 0; candidate < memoryProperties.memoryTypeCount; ++candidate) {
        const bool allowed = (typeBits & (1u << candidate)) != 0;
        const VkMemoryPropertyFlags flags = memoryProperties.memoryTypes[candidate].propertyFlags;
        if (allowed &&
            (flags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
                (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
            *index = candidate;
            return true;
        }
    }
    return false;
}

void destroy_probe() noexcept {
    if (g_state.device != VK_NULL_HANDLE) {
        if (g_probe.mapped != nullptr) {
            vkUnmapMemory(g_state.device, g_probe.memory);
        }
        if (g_probe.buffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(g_state.device, g_probe.buffer, nullptr);
        }
        if (g_probe.memory != VK_NULL_HANDLE) {
            vkFreeMemory(g_state.device, g_probe.memory, nullptr);
        }
    }
    g_probe = MotionProbeBuffer{};
}

int32_t ensure_probe() noexcept {
    if (g_probe.buffer != VK_NULL_HANDLE) {
        return kSuccess;
    }
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = kMotionProbeBytes;
    bufferInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VkBuffer buffer = VK_NULL_HANDLE;
    if (vkCreateBuffer(g_state.device, &bufferInfo, nullptr, &buffer) != VK_SUCCESS) {
        return kFailure;
    }
    g_probe.buffer = buffer;

    VkMemoryRequirements requirements{};
    vkGetBufferMemoryRequirements(g_state.device, buffer, &requirements);
    uint32_t memoryTypeIndex = 0;
    if (!find_host_memory_type(requirements.memoryTypeBits, &memoryTypeIndex)) {
        destroy_probe();
        return kFailure;
    }
    VkMemoryAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocateInfo.allocationSize = requirements.size;
    allocateInfo.memoryTypeIndex = memoryTypeIndex;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    if (vkAllocateMemory(g_state.device, &allocateInfo, nullptr, &memory) != VK_SUCCESS) {
        destroy_probe();
        return kFailure;
    }
    g_probe.memory = memory;
    if (vkBindBufferMemory(g_state.device, buffer, memory, 0) != VK_SUCCESS) {
        destroy_probe();
        return kFailure;
    }
    void* mapped = nullptr;
    if (vkMapMemory(g_state.device, memory, 0, kMotionProbeBytes, 0, &mapped) != VK_SUCCESS) {
        destroy_probe();
        return kFailure;
    }
    g_probe.mapped = static_cast<float*>(mapped);
    std::memset(g_probe.mapped, 0, kMotionProbeBytes);
    return kSuccess;
}

void write_probe_binding(VkWriteDescriptorSet& write, VkDescriptorSet set, const uint32_t binding,
                         VkDescriptorBufferInfo& info) noexcept {
    info.buffer = g_probe.buffer;
    info.offset = 0;
    info.range = VK_WHOLE_SIZE;
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.dstSet = set;
    write.dstBinding = binding;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    write.pBufferInfo = &info;
}

void barrier_probe(const VkCommandBuffer commandBuffer) noexcept {
    VkBufferMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.buffer = g_probe.buffer;
    barrier.offset = 0;
    barrier.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT, 0, 0, nullptr, 1, &barrier, 0, nullptr);
}

int32_t probe_slot() noexcept {
    return static_cast<int32_t>(g_probe.records % kMotionProbeRing);
}

void advance_probe() noexcept {
    g_probe.records++;
}

// Points one ring slot at this frame's depth view, the owned motion view, and the owned
// flipped motion view, reusing the slot already describing them whenever nothing changed -
// which, after the first frame, is every frame.
VkDescriptorSet bind_motion_descriptors(const uint64_t depthView) noexcept {
    DlssMotionPass& pass = g_state.motionPass;
    const uint64_t motionView = to_uint64(g_state.motionImage.view);
    const uint64_t flippedView = to_uint64(g_state.fgMotionImage.view);
    if (pass.boundSet >= 0 && pass.boundDepthView == depthView &&
        pass.boundMotionView == motionView && pass.boundFlippedView == flippedView) {
        return pass.sets[pass.boundSet];
    }

    const uint32_t slot = pass.nextSet;
    const VkDescriptorSet set = pass.sets[slot];

    VkDescriptorImageInfo depthInfo{};
    depthInfo.sampler = pass.sampler;
    depthInfo.imageView = from_uint64<VkImageView>(depthView);
    // The dispatch reads depth in the layout the transitions below leave it in.
    depthInfo.imageLayout = kDlssInputLayout;
    VkDescriptorImageInfo motionInfo{};
    motionInfo.imageView = g_state.motionImage.view;
    motionInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo flippedInfo{};
    flippedInfo.imageView = g_state.fgMotionImage.view;
    flippedInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkWriteDescriptorSet writes[4]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = set;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[0].pImageInfo = &depthInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = set;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &motionInfo;
    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = set;
    writes[2].dstBinding = 2;
    writes[2].descriptorCount = 1;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[2].pImageInfo = &flippedInfo;
    VkDescriptorBufferInfo probeInfo{};
    write_probe_binding(writes[3], set, 3, probeInfo);
    vkUpdateDescriptorSets(g_state.device, 4, writes, 0, nullptr);

    pass.boundSet = static_cast<int32_t>(slot);
    pass.boundDepthView = depthView;
    pass.boundMotionView = motionView;
    pass.boundFlippedView = flippedView;
    pass.nextSet = (slot + 1) % kMotionDescriptorRing;
    return set;
}

// Points one ring slot at this frame's depth view, the scene's velocity companion view, the
// owned motion view, and the owned flipped motion view, reusing the slot already describing
// them whenever nothing changed. The companion is a sampled input only and is never bound as
// a storage image: Minecraft creates its velocity target without storage usage, and the fill
// reads it exactly as the scene renderers wrote it.
VkDescriptorSet bind_velocity_fill_descriptors(const uint64_t depthView,
                                               const uint64_t velocityView) noexcept {
    DlssMotionPass& pass = g_velocityFill.pass;
    const uint64_t motionView = to_uint64(g_state.motionImage.view);
    const uint64_t flippedView = to_uint64(g_state.fgMotionImage.view);
    if (pass.boundSet >= 0 && pass.boundDepthView == depthView &&
        pass.boundMotionView == motionView && g_velocityFill.boundVelocityView == velocityView &&
        pass.boundFlippedView == flippedView) {
        return pass.sets[pass.boundSet];
    }

    const uint32_t slot = pass.nextSet;
    const VkDescriptorSet set = pass.sets[slot];

    VkDescriptorImageInfo depthInfo{};
    depthInfo.sampler = pass.sampler;
    depthInfo.imageView = from_uint64<VkImageView>(depthView);
    // The dispatch reads depth in the layout the transitions below leave it in.
    depthInfo.imageLayout = kDlssInputLayout;
    VkDescriptorImageInfo velocityInfo{};
    velocityInfo.sampler = pass.sampler;
    velocityInfo.imageView = from_uint64<VkImageView>(velocityView);
    // The companion rests in GENERAL - the layout the engine renders its colour attachments
    // in - and is sampled there, so no transition ever moves it.
    velocityInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo motionInfo{};
    motionInfo.imageView = g_state.motionImage.view;
    motionInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo flippedInfo{};
    flippedInfo.imageView = g_state.fgMotionImage.view;
    flippedInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkWriteDescriptorSet writes[5]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = set;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[0].pImageInfo = &depthInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = set;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[1].pImageInfo = &velocityInfo;
    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = set;
    writes[2].dstBinding = 2;
    writes[2].descriptorCount = 1;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[2].pImageInfo = &motionInfo;
    writes[3].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[3].dstSet = set;
    writes[3].dstBinding = 3;
    writes[3].descriptorCount = 1;
    writes[3].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[3].pImageInfo = &flippedInfo;
    VkDescriptorBufferInfo probeInfo{};
    write_probe_binding(writes[4], set, 4, probeInfo);
    vkUpdateDescriptorSets(g_state.device, 5, writes, 0, nullptr);

    pass.boundSet = static_cast<int32_t>(slot);
    pass.boundDepthView = depthView;
    pass.boundMotionView = motionView;
    pass.boundFlippedView = flippedView;
    g_velocityFill.boundVelocityView = velocityView;
    pass.nextSet = (slot + 1) % kMotionDescriptorRing;
    return set;
}

// Destroys one pass in the reverse of creation order. Every handle is checked because this
// also runs as the cleanup path of a partially created pass. Declared before the creator,
// which uses it for exactly that cleanup path. This never stalls: a half-built pass was
// never submitted, and the shutdown path performs the one device-wide stall before any
// live pass's handles are freed.
void destroy_dispatch_pass(DlssMotionPass& pass) noexcept {
    if (g_state.device != VK_NULL_HANDLE) {
        if (pass.pipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(g_state.device, pass.pipeline, nullptr);
        }
        if (pass.pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(g_state.device, pass.pipelineLayout, nullptr);
        }
        // The pool owns its sets; freeing them individually is neither needed nor allowed
        // without VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT.
        if (pass.descriptorPool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(g_state.device, pass.descriptorPool, nullptr);
        }
        if (pass.setLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(g_state.device, pass.setLayout, nullptr);
        }
        if (pass.sampler != VK_NULL_HANDLE) {
            vkDestroySampler(g_state.device, pass.sampler, nullptr);
        }
        if (pass.shader != VK_NULL_HANDLE) {
            vkDestroyShaderModule(g_state.device, pass.shader, nullptr);
        }
    }
    pass = DlssMotionPass{};
}

// Builds one whole dispatch pass - shader, sampler, set layout, pipeline layout, pool, ring
// of sets, pipeline - or leaves nothing behind. The two passes this module records differ
// only in the shader module, the push-constant range, and the descriptor binding layout they
// declare, so the shape is one function parameterized by those three.
//
// Like create_owned_image, each handle is created into a local and published only once its
// call succeeded, because Vulkan leaves the output parameter undefined on failure and the
// destroy path would then free garbage.
int32_t create_dispatch_pass(DlssMotionPass& pass, const uint32_t* spirv, const size_t spirvBytes,
                             const size_t pushConstantBytes, const VkDescriptorType* bindingTypes,
                             const uint32_t bindingCount) noexcept {
    if (pass.pipeline != VK_NULL_HANDLE) {
        return kSuccess;
    }

    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = spirvBytes;
    shaderInfo.pCode = spirv;
    VkShaderModule shader = VK_NULL_HANDLE;
    if (vkCreateShaderModule(g_state.device, &shaderInfo, nullptr, &shader) != VK_SUCCESS) {
        return kFailure;
    }
    pass.shader = shader;

    // The shaders read depth with texelFetch, so filtering and addressing never come into
    // play; the sampler exists only because a combined image sampler needs one.
    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_NEAREST;
    samplerInfo.minFilter = VK_FILTER_NEAREST;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.borderColor = VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK;
    VkSampler sampler = VK_NULL_HANDLE;
    if (vkCreateSampler(g_state.device, &samplerInfo, nullptr, &sampler) != VK_SUCCESS) {
        destroy_dispatch_pass(pass);
        return kFailure;
    }
    pass.sampler = sampler;

    VkDescriptorSetLayoutBinding bindings[kMaxDispatchBindings]{};
    for (uint32_t index = 0; index < bindingCount; ++index) {
        bindings[index].binding = index;
        bindings[index].descriptorType = bindingTypes[index];
        bindings[index].descriptorCount = 1;
        bindings[index].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo setLayoutInfo{};
    setLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setLayoutInfo.bindingCount = bindingCount;
    setLayoutInfo.pBindings = bindings;
    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    if (vkCreateDescriptorSetLayout(g_state.device, &setLayoutInfo, nullptr, &setLayout) !=
        VK_SUCCESS) {
        destroy_dispatch_pass(pass);
        return kFailure;
    }
    pass.setLayout = setLayout;

    VkPushConstantRange pushConstantRange{};
    pushConstantRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushConstantRange.offset = 0;
    pushConstantRange.size = static_cast<uint32_t>(pushConstantBytes);
    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &setLayout;
    pipelineLayoutInfo.pushConstantRangeCount = 1;
    pipelineLayoutInfo.pPushConstantRanges = &pushConstantRange;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    if (vkCreatePipelineLayout(g_state.device, &pipelineLayoutInfo, nullptr, &pipelineLayout) !=
        VK_SUCCESS) {
        destroy_dispatch_pass(pass);
        return kFailure;
    }
    pass.pipelineLayout = pipelineLayout;

    // Pool sizes follow the binding layout: one ring's worth of each declared type.
    VkDescriptorPoolSize poolSizes[kMaxDispatchBindings]{};
    uint32_t poolCount = 0;
    for (uint32_t index = 0; index < bindingCount; ++index) {
        const VkDescriptorType type = bindingTypes[index];
        uint32_t poolIndex = poolCount;
        for (uint32_t existing = 0; existing < poolCount; ++existing) {
            if (poolSizes[existing].type == type) {
                poolIndex = existing;
                break;
            }
        }
        if (poolIndex == poolCount) {
            poolSizes[poolCount].type = type;
            poolCount++;
        }
        poolSizes[poolIndex].descriptorCount += kMotionDescriptorRing;
    }
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = kMotionDescriptorRing;
    poolInfo.poolSizeCount = poolCount;
    poolInfo.pPoolSizes = poolSizes;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    if (vkCreateDescriptorPool(g_state.device, &poolInfo, nullptr, &descriptorPool) != VK_SUCCESS) {
        destroy_dispatch_pass(pass);
        return kFailure;
    }
    pass.descriptorPool = descriptorPool;

    VkDescriptorSetLayout ringLayouts[kMotionDescriptorRing];
    for (uint32_t slot = 0; slot < kMotionDescriptorRing; ++slot) {
        ringLayouts[slot] = setLayout;
    }
    VkDescriptorSetAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocateInfo.descriptorPool = descriptorPool;
    allocateInfo.descriptorSetCount = kMotionDescriptorRing;
    allocateInfo.pSetLayouts = ringLayouts;
    VkDescriptorSet sets[kMotionDescriptorRing] = {};
    if (vkAllocateDescriptorSets(g_state.device, &allocateInfo, sets) != VK_SUCCESS) {
        destroy_dispatch_pass(pass);
        return kFailure;
    }
    for (uint32_t slot = 0; slot < kMotionDescriptorRing; ++slot) {
        pass.sets[slot] = sets[slot];
    }

    VkPipelineShaderStageCreateInfo stageInfo{};
    stageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stageInfo.module = shader;
    stageInfo.pName = "main";
    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage = stageInfo;
    pipelineInfo.layout = pipelineLayout;
    VkPipeline pipeline = VK_NULL_HANDLE;
    if (vkCreateComputePipelines(g_state.device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr,
                                 &pipeline) != VK_SUCCESS) {
        destroy_dispatch_pass(pass);
        return kFailure;
    }
    pass.pipeline = pipeline;
    return kSuccess;
}

} // namespace

void destroy_motion_pass() noexcept {
    // One shutdown stall covers both live passes before either is destroyed: each pass has
    // been dispatched from, and the shutdown path admits no second device-idle wait. A
    // half-built pass never reaches here - the create path cleans it up itself - but the
    // pipeline checks keep a session that never built one of the two from stalling for it.
    if (g_state.device != VK_NULL_HANDLE &&
        (g_velocityFill.pass.pipeline != VK_NULL_HANDLE ||
         g_state.motionPass.pipeline != VK_NULL_HANDLE)) {
        wait_device_idle();
    }
    // The fill pass was created after the motion pass, so it goes first.
    destroy_dispatch_pass(g_velocityFill.pass);
    destroy_dispatch_pass(g_state.motionPass);
    destroy_probe();
    g_velocityFill = VelocityFillPass{};
}

int32_t create_motion_pass() noexcept {
    const int32_t probeResult = ensure_probe();
    if (probeResult != kSuccess) {
        return probeResult;
    }
    return create_dispatch_pass(g_state.motionPass, kMotionSpirV, sizeof(kMotionSpirV),
                                sizeof(DlssMotionPushConstants), kMotionBindingTypes,
                                sizeof(kMotionBindingTypes) / sizeof(kMotionBindingTypes[0]));
}

int32_t create_velocity_fill_pass() noexcept {
    const int32_t probeResult = ensure_probe();
    if (probeResult != kSuccess) {
        return probeResult;
    }
    return create_dispatch_pass(g_velocityFill.pass, kVelocityFillSpirV,
                                sizeof(kVelocityFillSpirV),
                                sizeof(DlssVelocityFillPushConstants), kVelocityFillBindingTypes,
                                sizeof(kVelocityFillBindingTypes) /
                                    sizeof(kVelocityFillBindingTypes[0]));
}

int32_t record_motion(const McDlssMotionInfo& info) noexcept {
    const int32_t passResult = create_motion_pass();
    if (passResult != kSuccess) {
        return passResult;
    }
    const VkDescriptorSet descriptorSet = bind_motion_descriptors(info.depth.view);

    // Same recording discipline as the evaluation: transitions and dispatch go onto the
    // engine's command buffer, and nothing here submits, waits, or idles the device.
    const VkCommandBuffer recordingBuffer = from_uint64<VkCommandBuffer>(info.command_buffer);
    const VkImageSubresourceRange depthRange = image_range_of(true);
    const VkImageLayout depthEntryLayout = current_layout_of(info.depth.image);
    record_layout_transition(recordingBuffer, from_uint64<VkImage>(info.depth.image), depthRange,
                             depthEntryLayout, kDlssInputLayout);
    const VkImageSubresourceRange motionRange{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    record_layout_transition(recordingBuffer, g_state.motionImage.image, motionRange,
                             g_state.motionImage.layout, VK_IMAGE_LAYOUT_GENERAL);
    g_state.motionImage.layout = VK_IMAGE_LAYOUT_GENERAL;
    // The flipped copy the FG tag names gets the same transition discipline: UNDEFINED into
    // GENERAL on the first frame, a no-op afterwards.
    record_layout_transition(recordingBuffer, g_state.fgMotionImage.image, motionRange,
                             g_state.fgMotionImage.layout, VK_IMAGE_LAYOUT_GENERAL);
    g_state.fgMotionImage.layout = VK_IMAGE_LAYOUT_GENERAL;

    DlssMotionPushConstants constants{};
    std::memcpy(constants.reprojection, info.reprojection, sizeof(constants.reprojection));
    constants.renderWidth = static_cast<int32_t>(info.render_width);
    constants.renderHeight = static_cast<int32_t>(info.render_height);
    constants.probeSlot = probe_slot();

    vkCmdBindPipeline(recordingBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, g_state.motionPass.pipeline);
    vkCmdBindDescriptorSets(recordingBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g_state.motionPass.pipelineLayout, 0, 1, &descriptorSet, 0, nullptr);
    vkCmdPushConstants(recordingBuffer, g_state.motionPass.pipelineLayout,
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(constants), &constants);
    // Rounded up to whole workgroups; the shader discards the surplus invocations.
    vkCmdDispatch(recordingBuffer, motion_workgroup_count(info.render_width),
                  motion_workgroup_count(info.render_height), 1);

    // The dispatch's writes are not visible to anything downstream without a barrier of
    // this pass's own - and that includes both images it wrote: the motion image the
    // evaluation reads, and the flipped copy the FG tag declares. The evaluation's
    // transition of the motion image happens to provide one today, but only because GENERAL
    // and the layout DLSS reads it in differ - make the layouts ever agree and
    // record_layout_transition emits nothing, leaving the evaluation reading whatever was
    // in the image before the dispatch. The flipped copy rests in GENERAL with no later
    // transition of its own ever, so its barrier is the only one it gets. The pass owns the
    // visibility of its own writes rather than inheriting it from a caller.
    VkImageMemoryBarrier writeBarriers[2]{};
    for (uint32_t i = 0; i < 2; ++i) {
        VkImageMemoryBarrier& barrier = writeBarriers[i];
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.subresourceRange = motionRange;
    }
    writeBarriers[0].image = g_state.motionImage.image;
    writeBarriers[1].image = g_state.fgMotionImage.image;
    vkCmdPipelineBarrier(recordingBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 2,
                         writeBarriers);

    // Depth goes back where Minecraft expects it, in the same recording. The motion image
    // stays in GENERAL, which is both where the next evaluation's transition starts from
    // and where a reader of this module's own image expects to find it.
    record_layout_transition(recordingBuffer, from_uint64<VkImage>(info.depth.image), depthRange,
                             kDlssInputLayout, depthEntryLayout);
    barrier_probe(recordingBuffer);
    advance_probe();
    return kSuccess;
}

int32_t record_velocity_fill(const McDlssFillVelocityInfo& info) noexcept {
    const int32_t passResult = create_velocity_fill_pass();
    if (passResult != kSuccess) {
        return passResult;
    }
    // The destination is the module's own motion image, the sole Streamline motion source;
    // the engine's velocity companion is a sampled input and never appears as storage.
    const VkDescriptorSet descriptorSet = bind_velocity_fill_descriptors(
        info.depth.view, info.velocity.view);

    // Same recording discipline as the motion pass: transitions and dispatch go onto the
    // engine's command buffer, and nothing here submits, waits, or idles the device.
    const VkCommandBuffer recordingBuffer = from_uint64<VkCommandBuffer>(info.command_buffer);
    const VkImageSubresourceRange depthRange = image_range_of(true);
    const VkImageSubresourceRange motionRange{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    const VkImageLayout depthEntryLayout = current_layout_of(info.depth.image);
    record_layout_transition(recordingBuffer, from_uint64<VkImage>(info.depth.image), depthRange,
                             depthEntryLayout, kDlssInputLayout);
    record_layout_transition(recordingBuffer, g_state.motionImage.image, motionRange,
                             g_state.motionImage.layout, VK_IMAGE_LAYOUT_GENERAL);
    g_state.motionImage.layout = VK_IMAGE_LAYOUT_GENERAL;
    // The flipped copy the FG tag names gets the same transition discipline as the camera
    // pass gives it: UNDEFINED into GENERAL on the first frame, a no-op afterwards.
    record_layout_transition(recordingBuffer, g_state.fgMotionImage.image, motionRange,
                             g_state.fgMotionImage.layout, VK_IMAGE_LAYOUT_GENERAL);
    g_state.fgMotionImage.layout = VK_IMAGE_LAYOUT_GENERAL;

    // The velocity companion rests in GENERAL - Minecraft renders its colour attachments in
    // it - and GENERAL is also where the dispatch samples it, so no layout transition can
    // order the dispatch behind the scene's velocity writes: layout equality alone is not
    // synchronization. The fill therefore owns an explicit barrier before the dispatch,
    // making the writes the scene renderers and clears just recorded visible to its sampled
    // reads. The broad source stage is deliberate: the scene's velocity producers are render
    // passes and clears this module cannot see, and ALL_COMMANDS is correct for every
    // producer.
    VkImageMemoryBarrier velocityReadBarrier{};
    velocityReadBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    velocityReadBarrier.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    velocityReadBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    velocityReadBarrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    velocityReadBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    velocityReadBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    velocityReadBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    velocityReadBarrier.image = from_uint64<VkImage>(info.velocity.image);
    velocityReadBarrier.subresourceRange = motionRange;
    vkCmdPipelineBarrier(recordingBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                         &velocityReadBarrier);

    DlssVelocityFillPushConstants constants{};
    std::memcpy(constants.reprojection, info.reprojection, sizeof(constants.reprojection));
    constants.renderWidth = static_cast<int32_t>(info.render_width);
    constants.renderHeight = static_cast<int32_t>(info.render_height);
    constants.reset = info.reset;
    constants.probeSlot = probe_slot();

    vkCmdBindPipeline(recordingBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                      g_velocityFill.pass.pipeline);
    vkCmdBindDescriptorSets(recordingBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g_velocityFill.pass.pipelineLayout, 0, 1, &descriptorSet, 0,
                            nullptr);
    vkCmdPushConstants(recordingBuffer, g_velocityFill.pass.pipelineLayout,
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(constants), &constants);
    // Rounded up to whole workgroups; the shader discards the surplus invocations.
    vkCmdDispatch(recordingBuffer, motion_workgroup_count(info.render_width),
                  motion_workgroup_count(info.render_height), 1);

    // The dispatch's writes to the motion image and its flipped copy are not visible to the
    // tag and the evaluation without a barrier of this pass's own: both images stay in
    // GENERAL, so no later transition of either can provide one. The fill owns the
    // visibility of its writes, exactly like the motion pass's explicit barrier, and the
    // flipped copy - which nothing transitions ever - gets its only barrier here.
    VkImageMemoryBarrier writeBarriers[2]{};
    for (uint32_t i = 0; i < 2; ++i) {
        VkImageMemoryBarrier& barrier = writeBarriers[i];
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.subresourceRange = motionRange;
    }
    writeBarriers[0].image = g_state.motionImage.image;
    writeBarriers[1].image = g_state.fgMotionImage.image;
    vkCmdPipelineBarrier(recordingBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 2,
                         writeBarriers);

    // Depth goes back where Minecraft expects it, in the same recording. The velocity
    // companion stays in GENERAL - the layout the engine rests it in and where the next
    // frame's scene renderers write it - and the motion image stays in GENERAL, where the
    // next evaluation's transition starts from.
    record_layout_transition(recordingBuffer, from_uint64<VkImage>(info.depth.image), depthRange,
                             kDlssInputLayout, depthEntryLayout);
    barrier_probe(recordingBuffer);
    advance_probe();
    return kSuccess;
}

int32_t query_motion_probe(float* motionX, float* motionY, float* depth, int32_t* slot) noexcept {
    if (motionX == nullptr || motionY == nullptr || depth == nullptr || slot == nullptr) {
        return kInvalidParameter;
    }
    if (g_probe.mapped == nullptr || g_probe.records < kMotionProbeRing) {
        return kNotInitialized;
    }
    const uint32_t readable = (g_probe.records - kMotionProbeRing) % kMotionProbeRing;
    const float* sample = g_probe.mapped + readable * 4;
    *motionX = sample[0];
    *motionY = sample[1];
    *depth = sample[2];
    *slot = static_cast<int32_t>(readable);
    return kSuccess;
}

} // namespace mc_dlss
