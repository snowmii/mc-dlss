package me.snowmii.dlss.api;

/**
 * One engine image: raw handles and format. Minecraft Vulkan images are typically
 * single-level, single-layer 2D; consumers derive range/aspect from role.
 */
public record ImageBinding(
	long view,
	long image,
	int format
) {}
