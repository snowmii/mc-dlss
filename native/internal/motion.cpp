#include "internal/motion.h"

#include <cstring>

namespace mc_dlss {
namespace {

// The workgroup size is duplicated from mc_dlss_motion.comp because the dispatch has to round
// the render size up to whole workgroups; the shader's own bounds check is what keeps the
// surplus invocations harmless.
constexpr uint32_t kMotionWorkgroupSize = 8;

constexpr uint32_t kMotionSpirV[] =
#include "mc_dlss_motion.spv.h"
    ;

// Push-constant block, matching mc_dlss_motion.comp exactly: a column-major mat4 followed by
// the render size.
struct DlssMotionPushConstants {
    float reprojection[16];
    int32_t renderWidth;
    int32_t renderHeight;
};

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

} // namespace

void destroy_motion_pass() noexcept {
    DlssMotionPass& pass = g_state.motionPass;
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

// Like create_owned_image, each handle is created into a local and published only once its
// call succeeded, because Vulkan leaves the output parameter undefined on failure and the
// destroy path would then free garbage.
int32_t create_motion_pass() noexcept {
    DlssMotionPass& pass = g_state.motionPass;
    if (pass.pipeline != VK_NULL_HANDLE) {
        return kSuccess;
    }

    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = sizeof(kMotionSpirV);
    shaderInfo.pCode = kMotionSpirV;
    VkShaderModule shader = VK_NULL_HANDLE;
    if (vkCreateShaderModule(g_state.device, &shaderInfo, nullptr, &shader) != VK_SUCCESS) {
        return kFailure;
    }
    pass.shader = shader;

    // The shader reads depth with texelFetch, so filtering and addressing never come into
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
        destroy_motion_pass();
        return kFailure;
    }
    pass.sampler = sampler;

    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setLayoutInfo{};
    setLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setLayoutInfo.bindingCount = 2;
    setLayoutInfo.pBindings = bindings;
    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    if (vkCreateDescriptorSetLayout(g_state.device, &setLayoutInfo, nullptr, &setLayout) !=
        VK_SUCCESS) {
        destroy_motion_pass();
        return kFailure;
    }
    pass.setLayout = setLayout;

    VkPushConstantRange pushConstantRange{};
    pushConstantRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushConstantRange.offset = 0;
    pushConstantRange.size = sizeof(DlssMotionPushConstants);
    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &setLayout;
    pipelineLayoutInfo.pushConstantRangeCount = 1;
    pipelineLayoutInfo.pPushConstantRanges = &pushConstantRange;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    if (vkCreatePipelineLayout(g_state.device, &pipelineLayoutInfo, nullptr, &pipelineLayout) !=
        VK_SUCCESS) {
        destroy_motion_pass();
        return kFailure;
    }
    pass.pipelineLayout = pipelineLayout;

    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[0].descriptorCount = kMotionDescriptorRing;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = kMotionDescriptorRing;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = kMotionDescriptorRing;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    if (vkCreateDescriptorPool(g_state.device, &poolInfo, nullptr, &descriptorPool) != VK_SUCCESS) {
        destroy_motion_pass();
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
        destroy_motion_pass();
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
        destroy_motion_pass();
        return kFailure;
    }
    pass.pipeline = pipeline;
    return kSuccess;
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

} // namespace mc_dlss
