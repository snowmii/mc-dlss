package me.snowmii.dlss.client

import net.minecraft.client.TextureFilteringMethod

object TextureFilteringPolicy {
	@JvmStatic
	fun withoutRgss(
		methods: Array<TextureFilteringMethod>,
		lock: Boolean,
	): Array<TextureFilteringMethod> {
		if (!lock || methods.none { it == TextureFilteringMethod.RGSS }) {
			return methods
		}
		return methods.filter { it != TextureFilteringMethod.RGSS }.toTypedArray()
	}

	@JvmStatic
	fun effective(method: TextureFilteringMethod, lock: Boolean): TextureFilteringMethod =
		if (lock && method == TextureFilteringMethod.RGSS) TextureFilteringMethod.NONE else method
}
