#include "internal/motion.h"

#include <cstring>

namespace mc_dlss {
namespace {

// The workgroup size is duplicated from the shaders because the dispatch has to round the
// render size up to whole workgroups; the shaders' own bounds checks are what keep the
// surplus invocations harmless.
constexpr uint32_t kMotionWorkgroupSize = 8;

constexpr uint32_t kMotionSpirV[] =
#include "mc_dlss_motion.spv.h"
    ;

constexpr uint32_t kVelocityFillSpirV[] =
#include "mc_dlss_velocity_fill.spv.h"
    ;

// The largest descriptor-set binding layout either dispatch pass declares. The camera-only
// pass binds two descriptors, the velocity fill binds three.
constexpr uint32_t kMaxDispatchBindings = 3;

// Push-constant block, matching mc_dlss_motion.comp exactly: a column-major mat4 followed by
// the render size.
struct DlssMotionPushConstants {
    float reprojection[16];
    int32_t renderWidth;
    int32_t renderHeight;
};

// Push-constant block, matching mc_dlss_velocity_fill.comp exactly: the same mat4 and render
// size, plus the reset flag.
struct DlssVelocityFillPushConstants {
    float reprojection[16];
    int32_t renderWidth;
    int32_t renderHeight;
    int32_t reset;
};

// The two passes this module records declare different descriptor layouts: the camera-only
// pass samples depth into a storage destination, and the fill samples depth and the engine's
// velocity companion into the same storage destination.
constexpr VkDescriptorType kMotionBindingTypes[] = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                                                    VK_DESCRIPTOR_TYPE_STORAGE_IMAGE};
constexpr VkDescriptorType kVelocityFillBindingTypes[] = {
    VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
    VK_DESCRIPTOR_TYPE_STORAGE_IMAGE};

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

// Points one ring slot at this frame's depth view and the owned motion view, reusing the
// slot already describing them whenever nothing changed - which, after the first frame, is
// every frame.
VkDescriptorSet bind_motion_descriptors(const uint64_t depthView) noexcept {
    DlssMotionPass& pass = g_state.motionPass;
    const uint64_t motionView = to_uint64(g_state.motionImage.view);
    if (pass.boundSet >= 0 && pass.boundDepthView == depthView &&
        pass.boundMotionView == motionView) {
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

    VkWriteDescriptorSet writes[2]{};
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
    vkUpdateDescriptorSets(g_state.device, 2, writes, 0, nullptr);

    pass.boundSet = static_cast<int32_t>(slot);
    pass.boundDepthView = depthView;
    pass.boundMotionView = motionView;
    pass.nextSet = (slot + 1) % kMotionDescriptorRing;
    return set;
}

// Points one ring slot at this frame's depth view, the scene's velocity companion view, and
// the owned motion view, reusing the slot already describing them whenever nothing changed.
// The companion is a sampled input only and is never bound as a storage image: Minecraft
// creates its velocity target without storage usage, and the fill reads it exactly as the
// scene renderers wrote it.
VkDescriptorSet bind_velocity_fill_descriptors(const uint64_t depthView,
                                               const uint64_t velocityView) noexcept {
    DlssMotionPass& pass = g_velocityFill.pass;
    const uint64_t motionView = to_uint64(g_state.motionImage.view);
    if (pass.boundSet >= 0 && pass.boundDepthView == depthView &&
        pass.boundMotionView == motionView && g_velocityFill.boundVelocityView == velocityView) {
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

    VkWriteDescriptorSet writes[3]{};
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
    vkUpdateDescriptorSets(g_state.device, 3, writes, 0, nullptr);

    pass.boundSet = static_cast<int32_t>(slot);
    pass.boundDepthView = depthView;
    pass.boundMotionView = motionView;
    g_velocityFill.boundVelocityView = velocityView;
    pass.nextSet = (slot + 1) % kMotionDescriptorRing;
    return set;
}

// Destroys one pass in the reverse of creation order. Every handle is checked because this
// also runs as the cleanup path of a partially created pass. Declared before the creator,
// which uses it for exactly that cleanup path.
void destroy_dispatch_pass(DlssMotionPass& pass) noexcept {
    if (g_state.device != VK_NULL_HANDLE) {
        // A fully built pass has been dispatched from; a half-built one never was, and the
        // stall costs nothing there because nothing referencing it was ever submitted.
        if (pass.pipeline != VK_NULL_HANDLE) {
            wait_device_idle();
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

    // Pool sizes follow the binding layout: one ring's worth of each declared type, with the
    // sampler and storage counts accumulated across the layout's bindings.
    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    for (uint32_t index = 0; index < bindingCount; ++index) {
        const uint32_t poolIndex =
            bindingTypes[index] == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE ? 1 : 0;
        poolSizes[poolIndex].descriptorCount += kMotionDescriptorRing;
    }
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = kMotionDescriptorRing;
    poolInfo.poolSizeCount = 2;
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
    // The fill pass was created after the motion pass, so it goes first; each destroy waits
    // only when its own pipeline was ever dispatched from.
    destroy_dispatch_pass(g_velocityFill.pass);
    destroy_dispatch_pass(g_state.motionPass);
    g_velocityFill = VelocityFillPass{};
}

int32_t create_motion_pass() noexcept {
    return create_dispatch_pass(g_state.motionPass, kMotionSpirV, sizeof(kMotionSpirV),
                                sizeof(DlssMotionPushConstants), kMotionBindingTypes,
                                sizeof(kMotionBindingTypes) / sizeof(kMotionBindingTypes[0]));
}

int32_t create_velocity_fill_pass() noexcept {
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

    DlssMotionPushConstants constants{};
    std::memcpy(constants.reprojection, info.reprojection, sizeof(constants.reprojection));
    constants.renderWidth = static_cast<int32_t>(info.render_width);
    constants.renderHeight = static_cast<int32_t>(info.render_height);

    vkCmdBindPipeline(recordingBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, g_state.motionPass.pipeline);
    vkCmdBindDescriptorSets(recordingBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g_state.motionPass.pipelineLayout, 0, 1, &descriptorSet, 0, nullptr);
    vkCmdPushConstants(recordingBuffer, g_state.motionPass.pipelineLayout,
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(constants), &constants);
    // Rounded up to whole workgroups; the shader discards the surplus invocations.
    vkCmdDispatch(recordingBuffer,
                  (info.render_width + kMotionWorkgroupSize - 1) / kMotionWorkgroupSize,
                  (info.render_height + kMotionWorkgroupSize - 1) / kMotionWorkgroupSize, 1);

    // The dispatch's writes are not visible to anything downstream without a barrier of
    // this pass's own. The evaluation's transition of the motion image happens to provide
    // one today, but only because GENERAL and the layout DLSS reads it in differ - make
    // the layouts ever agree and record_layout_transition emits nothing, leaving the
    // evaluation reading whatever was in the image before the dispatch. The pass owns the
    // visibility of its own writes rather than inheriting it from a caller.
    VkImageMemoryBarrier motionWriteBarrier{};
    motionWriteBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    motionWriteBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    motionWriteBarrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    motionWriteBarrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    motionWriteBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    motionWriteBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    motionWriteBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    motionWriteBarrier.image = g_state.motionImage.image;
    motionWriteBarrier.subresourceRange = motionRange;
    vkCmdPipelineBarrier(recordingBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1,
                         &motionWriteBarrier);

    // Depth goes back where Minecraft expects it, in the same recording. The motion image
    // stays in GENERAL, which is both where the next evaluation's transition starts from
    // and where a reader of this module's own image expects to find it.
    record_layout_transition(recordingBuffer, from_uint64<VkImage>(info.depth.image), depthRange,
                             kDlssInputLayout, depthEntryLayout);
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

    vkCmdBindPipeline(recordingBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                      g_velocityFill.pass.pipeline);
    vkCmdBindDescriptorSets(recordingBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g_velocityFill.pass.pipelineLayout, 0, 1, &descriptorSet, 0,
                            nullptr);
    vkCmdPushConstants(recordingBuffer, g_velocityFill.pass.pipelineLayout,
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(constants), &constants);
    // Rounded up to whole workgroups; the shader discards the surplus invocations.
    vkCmdDispatch(recordingBuffer,
                  (info.render_width + kMotionWorkgroupSize - 1) / kMotionWorkgroupSize,
                  (info.render_height + kMotionWorkgroupSize - 1) / kMotionWorkgroupSize, 1);

    // The dispatch's writes to the motion image are not visible to the tag and the
    // evaluation without a barrier of this pass's own: the motion image stays in GENERAL, so
    // no later transition of it can provide one. The fill owns the visibility of its writes,
    // exactly like the motion pass's explicit barrier.
    VkImageMemoryBarrier motionWriteBarrier{};
    motionWriteBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    motionWriteBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    motionWriteBarrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    motionWriteBarrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    motionWriteBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    motionWriteBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    motionWriteBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    motionWriteBarrier.image = g_state.motionImage.image;
    motionWriteBarrier.subresourceRange = motionRange;
    vkCmdPipelineBarrier(recordingBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1,
                         &motionWriteBarrier);

    // Depth goes back where Minecraft expects it, in the same recording. The velocity
    // companion stays in GENERAL - the layout the engine rests it in and where the next
    // frame's scene renderers write it - and the motion image stays in GENERAL, where the
    // next evaluation's transition starts from.
    record_layout_transition(recordingBuffer, from_uint64<VkImage>(info.depth.image), depthRange,
                             kDlssInputLayout, depthEntryLayout);
    return kSuccess;
}

} // namespace mc_dlss
