package me.snowmii.dlss.client

import net.minecraft.client.TextureFilteringMethod
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class TextureFilteringPolicyTest {
	private val all = arrayOf(
		TextureFilteringMethod.NONE,
		TextureFilteringMethod.RGSS,
		TextureFilteringMethod.ANISOTROPIC,
	)

	@Test
	fun `SR off leaves RGSS in the cycle`() {
		assertSame(all, TextureFilteringPolicy.withoutRgss(all, false))
		assertEquals(TextureFilteringMethod.RGSS, TextureFilteringPolicy.effective(TextureFilteringMethod.RGSS, false))
	}

	@Test
	fun `SR on drops RGSS from the cycle and reads it as None`() {
		assertArrayEquals(
			arrayOf(TextureFilteringMethod.NONE, TextureFilteringMethod.ANISOTROPIC),
			TextureFilteringPolicy.withoutRgss(all, true),
		)
		assertEquals(TextureFilteringMethod.NONE, TextureFilteringPolicy.effective(TextureFilteringMethod.RGSS, true))
		assertEquals(
			TextureFilteringMethod.ANISOTROPIC,
			TextureFilteringPolicy.effective(TextureFilteringMethod.ANISOTROPIC, true),
		)
	}

	@Test
	fun `already-filtered lists are left in place`() {
		val noneAndAf = arrayOf(TextureFilteringMethod.NONE, TextureFilteringMethod.ANISOTROPIC)
		assertSame(noneAndAf, TextureFilteringPolicy.withoutRgss(noneAndAf, true))
	}
}
