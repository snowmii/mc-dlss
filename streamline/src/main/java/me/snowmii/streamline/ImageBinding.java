package me.snowmii.streamline;

/**
 * One engine image: raw handles and format. Subresource range is absent: Minecraft Vulkan
 * images are single-level, single-layer 2D, and native derives range and aspect from the
 * binding's role.
 */
public record ImageBinding(
	long view,
	long image,
	int format
) {}