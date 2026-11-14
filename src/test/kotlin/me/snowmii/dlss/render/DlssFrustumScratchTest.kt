package me.snowmii.dlss.render

import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class DlssFrustumScratchTest {
        @Test
        fun `frustum calculation writes into a caller-owned scratch array`() {
                val projection = Matrix4f().perspective(1.1f, 1.7f, 0.2f, 90f)
                val inverse = Matrix4f(projection).invert()
                val expected = dlssFrustum(projection, inverse)
                val scratch = FloatArray(4)

                val actual = dlssFrustum(projection, inverse, scratch)

                assertSame(scratch, actual)
                assertArrayEquals(expected, actual, 0f)
        }
}
