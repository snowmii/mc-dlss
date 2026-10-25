package me.snowmii.dlss.render;

/** Actual Vulkan storage usage recorded when an engine render target is allocated. */
public interface StoragePresentationImage {
    boolean mcDlssStorageWritable();
}
