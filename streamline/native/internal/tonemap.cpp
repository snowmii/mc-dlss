#include "internal/tonemap.h"

#include "internal/common.h"
#include "internal/motion.h"

namespace mc_dlss {
namespace {

constexpr uint32_t kToneMapSpirV[] =
#include "mc_dlss_tonemap.spv.h"
    ;

struct ToneMapPushConstants {
    int32_t outputWidth;
    int32_t outputHeight;
    float exposure;
    int32_t hasReflection;
};
static_assert(sizeof(ToneMapPushConstants) == 16);

struct ToneMapPass {
    VkShaderModule shader = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet sets[kMotionDescriptorRing] = {};
    uint32_t nextSet = 0;
    int32_t boundSet = -1;
    uint64_t boundInputView = 0;
    uint64_t boundOutputView = 0;
    uint64_t boundReflectionView = 0;
};

ToneMapPass g_toneMap;
constexpr VkDescriptorType kBindings[] = {
    VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
    VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
    VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
};

void destroy_pass() noexcept {
    if (g_state.device != VK_NULL_HANDLE) {
        if (g_toneMap.pipeline != VK_NULL_HANDLE) vkDestroyPipeline(g_state.device, g_toneMap.pipeline, nullptr);
        if (g_toneMap.pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(g_state.device, g_toneMap.pipelineLayout, nullptr);
        if (g_toneMap.descriptorPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(g_state.device, g_toneMap.descriptorPool, nullptr);
        if (g_toneMap.setLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(g_state.device, g_toneMap.setLayout, nullptr);
        if (g_toneMap.sampler != VK_NULL_HANDLE) vkDestroySampler(g_state.device, g_toneMap.sampler, nullptr);
        if (g_toneMap.shader != VK_NULL_HANDLE) vkDestroyShaderModule(g_state.device, g_toneMap.shader, nullptr);
    }
    g_toneMap = ToneMapPass{};
}

int32_t create_pass() noexcept {
    if (g_toneMap.pipeline != VK_NULL_HANDLE) return kSuccess;

    VkShaderModuleCreateInfo shaderInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    shaderInfo.codeSize = sizeof(kToneMapSpirV);
    shaderInfo.pCode = kToneMapSpirV;
    if (vkCreateShaderModule(g_state.device, &shaderInfo, nullptr, &g_toneMap.shader) != VK_SUCCESS) {
        destroy_pass();
        return kFailure;
    }

    VkSamplerCreateInfo samplerInfo{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    samplerInfo.magFilter = VK_FILTER_NEAREST;
    samplerInfo.minFilter = VK_FILTER_NEAREST;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.borderColor = VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK;
    if (vkCreateSampler(g_state.device, &samplerInfo, nullptr, &g_toneMap.sampler) != VK_SUCCESS) {
        destroy_pass();
        return kFailure;
    }

    VkDescriptorSetLayoutBinding bindings[3]{};
    for (uint32_t i = 0; i < 3; ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType = kBindings[i];
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    layoutInfo.bindingCount = 3;
    layoutInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(g_state.device, &layoutInfo, nullptr, &g_toneMap.setLayout) != VK_SUCCESS) {
        destroy_pass();
        return kFailure;
    }

    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.size = sizeof(ToneMapPushConstants);
    VkPipelineLayoutCreateInfo pipelineLayoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &g_toneMap.setLayout;
    pipelineLayoutInfo.pushConstantRangeCount = 1;
    pipelineLayoutInfo.pPushConstantRanges = &pushRange;
    if (vkCreatePipelineLayout(g_state.device, &pipelineLayoutInfo, nullptr, &g_toneMap.pipelineLayout) != VK_SUCCESS) {
        destroy_pass();
        return kFailure;
    }

    VkDescriptorPoolSize poolSizes[2] = {
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 2 * kMotionDescriptorRing},
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, kMotionDescriptorRing},
    };
    VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.maxSets = kMotionDescriptorRing;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(g_state.device, &poolInfo, nullptr, &g_toneMap.descriptorPool) != VK_SUCCESS) {
        destroy_pass();
        return kFailure;
    }

    VkDescriptorSetLayout layouts[kMotionDescriptorRing]{};
    for (uint32_t i = 0; i < kMotionDescriptorRing; ++i) layouts[i] = g_toneMap.setLayout;
    VkDescriptorSetAllocateInfo allocateInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    allocateInfo.descriptorPool = g_toneMap.descriptorPool;
    allocateInfo.descriptorSetCount = kMotionDescriptorRing;
    allocateInfo.pSetLayouts = layouts;
    if (vkAllocateDescriptorSets(g_state.device, &allocateInfo, g_toneMap.sets) != VK_SUCCESS) {
        destroy_pass();
        return kFailure;
    }

    VkPipelineShaderStageCreateInfo stageInfo{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    stageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stageInfo.module = g_toneMap.shader;
    stageInfo.pName = "main";
    VkComputePipelineCreateInfo pipelineInfo{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    pipelineInfo.stage = stageInfo;
    pipelineInfo.layout = g_toneMap.pipelineLayout;
    if (vkCreateComputePipelines(g_state.device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr,
                                 &g_toneMap.pipeline) != VK_SUCCESS) {
        destroy_pass();
        return kFailure;
    }
    return kSuccess;
}

VkDescriptorSet bind_descriptors(const McDlssImage& reflection, const McDlssImage& output) noexcept {
    const uint64_t reflectionView = reflection.view ? reflection.view : to_uint64(g_state.rrOutputImage.view);
    const uint64_t inputView = to_uint64(g_state.rrOutputImage.view);
    const uint64_t outputView = output.view;
    if (g_toneMap.boundSet >= 0 && g_toneMap.boundInputView == inputView &&
        g_toneMap.boundOutputView == outputView && g_toneMap.boundReflectionView == reflectionView) {
        return g_toneMap.sets[g_toneMap.boundSet];
    }

    const uint32_t slot = g_toneMap.nextSet;
    VkDescriptorImageInfo inputInfo{g_toneMap.sampler, g_state.rrOutputImage.view,
                                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkDescriptorImageInfo outputInfo{VK_NULL_HANDLE, from_uint64<VkImageView>(outputView),
                                     VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorImageInfo reflectionInfo{g_toneMap.sampler, from_uint64<VkImageView>(reflectionView),
                                         VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet writes[3]{};
    for (uint32_t i = 0; i < 3; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = g_toneMap.sets[slot];
        writes[i].dstBinding = i;
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = kBindings[i];
        writes[i].pImageInfo = i == 0 ? &inputInfo : (i == 1 ? &outputInfo : &reflectionInfo);
    }
    vkUpdateDescriptorSets(g_state.device, 3, writes, 0, nullptr);
    g_toneMap.boundSet = static_cast<int32_t>(slot);
    g_toneMap.boundReflectionView = reflectionView;
    g_toneMap.boundInputView = inputView;
    g_toneMap.boundOutputView = outputView;
    g_toneMap.nextSet = (slot + 1) % kMotionDescriptorRing;
    return g_toneMap.sets[slot];
}

} // namespace

void destroy_tone_map_pass() noexcept {
    if (g_state.device != VK_NULL_HANDLE && g_toneMap.pipeline != VK_NULL_HANDLE) {
        wait_device_idle();
    }
    destroy_pass();
}

void invalidate_tone_map_bindings() noexcept {
    g_toneMap.boundSet = -1;
    g_toneMap.boundInputView = 0;
    g_toneMap.boundOutputView = 0;
}

int32_t record_tone_map(const VkCommandBuffer commandBuffer, float exposure,
                       const McDlssImage& reflection, const McDlssImage& output) noexcept {
    const int32_t passResult = create_pass();
    if (passResult != kSuccess) return passResult;

    const VkImageSubresourceRange range = image_range_of(false);
    const VkImageLayout inputEntry = current_layout_of(to_uint64(g_state.rrOutputImage.image));
    const VkImageLayout outputEntry = current_layout_of(output.image);
    const bool hasReflection = reflection.image != 0 && reflection.view != 0;
    const VkImageLayout reflectionEntry = hasReflection ? current_layout_of(reflection.image) : VK_IMAGE_LAYOUT_UNDEFINED;
    const bool separateReflection = hasReflection && reflection.image != to_uint64(g_state.rrOutputImage.image);
    // NGX owns the HDR producer; keep its boundary conservative. The destination may
    // already be GENERAL, but prior engine reads/writes must still finish before overwrite.
    const ImageTransitionDesc before[] = {
        {g_state.rrOutputImage.image, range, inputEntry, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
         VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT},
        {from_uint64<VkImage>(output.image), range, outputEntry, VK_IMAGE_LAYOUT_GENERAL,
         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
         VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_SHADER_WRITE_BIT},
        {separateReflection ? from_uint64<VkImage>(reflection.image) : VK_NULL_HANDLE, range,
         reflectionEntry, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
         VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT},
    };
    record_batched_transitions(commandBuffer, before, 3);
    const VkDescriptorSet descriptors = bind_descriptors(reflection, output);
    const ToneMapPushConstants constants{
        static_cast<int32_t>(g_state.outputWidth),
        static_cast<int32_t>(g_state.outputHeight),
        exposure > 0.0f ? exposure : 1.0f,
        hasReflection ? 1 : 0,
    };
    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, g_toneMap.pipeline);
    vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g_toneMap.pipelineLayout, 0, 1, &descriptors, 0, nullptr);
    vkCmdPushConstants(commandBuffer, g_toneMap.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(constants), &constants);
    vkCmdDispatch(commandBuffer, motion_workgroup_count(g_state.outputWidth),
                  motion_workgroup_count(g_state.outputHeight), 1);

    const ImageTransitionDesc after[] = {
        {g_state.rrOutputImage.image, range, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, inputEntry,
         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
         VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT},
        {from_uint64<VkImage>(output.image), range, VK_IMAGE_LAYOUT_GENERAL, outputEntry,
         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
         VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT},
        {separateReflection ? from_uint64<VkImage>(reflection.image) : VK_NULL_HANDLE, range,
         VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, reflectionEntry,
         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
         VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT},
    };
    record_batched_transitions(commandBuffer, after, 3);
    return kSuccess;
}

} // namespace mc_dlss
