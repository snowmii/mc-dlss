package me.snowmii.dlss.client

import net.minecraft.client.TextureFilteringMethod

/**
 * RGSS is vanilla/Sodium spatial AA. Stacking it with SR smears on camera motion.
 *
 * The stored Texture Filtering option is not rewritten: while SR is on, reads of RGSS behave as
 * None (so vanilla {@code UseRgss} and Sodium {@code u_UseRGSS} stay off) and the cycle skips
 * RGSS. Turning SR off restores the stored value, including RGSS.
 */
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
