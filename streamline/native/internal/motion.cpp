#include "internal/motion.h"

#include <cstring>

namespace mc_dlss {
namespace {

constexpr uint32_t kMotionSpirV[] =
#include "mc_dlss_motion.spv.h"
    ;

constexpr uint32_t kMaxDispatchBindings = 4;

struct DlssMotionPushConstants {
    float reprojection[16];
    int32_t renderWidth;
    int32_t renderHeight;
    int32_t probeSlot;
    int32_t fgConsumingFrame;
};

constexpr VkDescriptorType kMotionBindingTypes[] = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                                                    VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                                    VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                                    VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};

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

VkDescriptorSet bind_motion_descriptors(const uint64_t depthView) noexcept {
    DlssMotionPass& pass = g_state.motionPass;
    const uint64_t motionView = to_uint64(g_state.motionImage.view);
    const uint64_t flippedView = to_uint64(g_state.fgMotionImage.view);
    const uint64_t views[] = {depthView, motionView, flippedView, 0};
    for (uint32_t i = 0; i < kMotionDescriptorRing; ++i) {
        if (std::memcmp(pass.boundViews[i], views, sizeof(views)) == 0) return pass.sets[i];
    }

    const uint32_t slot = pass.nextSet;
    const VkDescriptorSet set = pass.sets[slot];

    VkDescriptorImageInfo depthInfo{};
    depthInfo.sampler = pass.sampler;
    depthInfo.imageView = from_uint64<VkImageView>(depthView);
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

    std::memcpy(pass.boundViews[slot], views, sizeof(views));
    pass.nextSet = (slot + 1) % kMotionDescriptorRing;
    return set;
}

void destroy_dispatch_pass(DlssMotionPass& pass) noexcept {
    if (g_state.device != VK_NULL_HANDLE) {
        if (pass.pipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(g_state.device, pass.pipeline, nullptr);
        }
        if (pass.pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(g_state.device, pass.pipelineLayout, nullptr);
        }
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

}

void invalidate_motion_bindings() noexcept {
    std::memset(g_state.motionPass.boundViews, 0, sizeof(g_state.motionPass.boundViews));
}

void destroy_motion_pass() noexcept {
    if (g_state.device != VK_NULL_HANDLE && g_state.motionPass.pipeline != VK_NULL_HANDLE) {
        wait_device_idle();
    }
    destroy_dispatch_pass(g_state.motionPass);
    destroy_probe();
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

int32_t record_motion(const McDlssMotionInfo& info) noexcept {
    const int32_t passResult = create_motion_pass();
    if (passResult != kSuccess) {
        return passResult;
    }
    const VkDescriptorSet descriptorSet = bind_motion_descriptors(info.depth.view);

    const VkCommandBuffer recordingBuffer = from_uint64<VkCommandBuffer>(info.command_buffer);
    const VkImageSubresourceRange depthRange = depth_range_of(info.depth);
    const VkImageSubresourceRange motionRange{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    const VkImageLayout depthEntryLayout = current_layout_of(info.depth.image);

    ImageTransitionDesc preMotionDescs[3];
    uint32_t preCount = 0;
    preMotionDescs[preCount++] = {from_uint64<VkImage>(info.depth.image), depthRange, depthEntryLayout, kDlssInputLayout,
         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
         VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT};
    preMotionDescs[preCount++] = {g_state.motionImage.image, motionRange, g_state.motionImage.layout, VK_IMAGE_LAYOUT_GENERAL,
         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
         VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_SHADER_WRITE_BIT};
    if (info.fg_consuming_frame != 0) {
        preMotionDescs[preCount++] = {g_state.fgMotionImage.image, motionRange, g_state.fgMotionImage.layout, VK_IMAGE_LAYOUT_GENERAL,
             VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
             VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_SHADER_WRITE_BIT};
        g_state.fgMotionImage.layout = VK_IMAGE_LAYOUT_GENERAL;
    }
    record_batched_transitions(recordingBuffer, preMotionDescs, preCount);
    g_state.motionImage.layout = VK_IMAGE_LAYOUT_GENERAL;

    DlssMotionPushConstants constants{};
    std::memcpy(constants.reprojection, info.reprojection, sizeof(constants.reprojection));
    constants.renderWidth = static_cast<int32_t>(info.render_width);
    constants.renderHeight = static_cast<int32_t>(info.render_height);
    constants.probeSlot = probe_slot();
    constants.fgConsumingFrame = static_cast<int32_t>(info.fg_consuming_frame);

    vkCmdBindPipeline(recordingBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, g_state.motionPass.pipeline);
    vkCmdBindDescriptorSets(recordingBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g_state.motionPass.pipelineLayout, 0, 1, &descriptorSet, 0, nullptr);
    vkCmdPushConstants(recordingBuffer, g_state.motionPass.pipelineLayout,
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(constants), &constants);
    vkCmdDispatch(recordingBuffer, motion_workgroup_count(info.render_width),
                  motion_workgroup_count(info.render_height), 1);

    ImageTransitionDesc postMotionDescs[3];
    uint32_t postCount = 0;
    postMotionDescs[postCount++] = {g_state.motionImage.image, motionRange, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
         VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT};
    if (info.fg_consuming_frame != 0) {
        postMotionDescs[postCount++] = {g_state.fgMotionImage.image, motionRange, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
             VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT};
    }
    postMotionDescs[postCount++] = {from_uint64<VkImage>(info.depth.image), depthRange, kDlssInputLayout, depthEntryLayout,
         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
         VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT};
    record_batched_transitions(recordingBuffer, postMotionDescs, postCount);
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

}
