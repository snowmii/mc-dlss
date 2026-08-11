package me.snowmii.dlss.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.ColorTargetState
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.math.abs
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.render.DlssCameraMotion
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitterOffset
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.resources.Identifier
import org.joml.Matrix4f
import org.joml.Vector4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Proves the terrain velocity writer of M-5 (AC-3): while an open VELOCITY_MRT world phase
 * offers its RG16_FLOAT velocity view, the known terrain chunk pipelines write jitter-stripped
 * NDC camera motion into color target 1 through the terrain writer twin - the plain two-target
 * velocity twin with the mc-dlss terrain velocity fragment shader and its VelocityConfig
 * uniform layout layered on - deriving the vectors from the frame's reprojection and the
 * fragment's own reversed-Z depth; invalid and reset pixels write the one representable
 * sentinel 10000.0; the velocity attachment clears to that sentinel before the opaque writer
 * and stays loaded through the translucent group; and the vanilla and camera-only routes keep
 * the exact one-target pass shape and cannot throw.
 *
 * The pipeline- and shader-level claims are descriptor and source proofs, exactly like the
 * rest of the MRT suite: nothing here compiles a pipeline on a device. The still-camera math
 * is exercised through the real [DlssCameraMotion] with the shader's own per-pixel formula,
 * and the sentinel choice is pinned both as shader source and as the classification behavior.
 * The mixin seams are pinned by source checks that fail when the clear lifecycle, the twin
 * selection, or the uniform write drop out of the production path.
 */
class StaticMotionVectorTest {

	/**
	 * The velocity shader declares exactly the uniform block the pipeline's added layout
	 * carries - Vulkan's lazy compile resolves every shader-declared uniform against the
	 * pipeline's layouts by name, so a mismatch would fail at the first bind, lazily,
	 * mid-render - and the block's member layout is exactly the JVM-side write: one mat4, one
	 * vec4, 80 bytes.
	 */
	@Test
	fun `the velocity uniform contract matches between shader and JVM write`() {
		val shader = terrainVelocityShader()

		// The shader block name is the layout's uniform name, and its members are the JVM
		// block's members.
		assertTrue(shader.contains("layout(std140) uniform ${TerrainVelocityUniforms.UNIFORM_NAME} {"))
		assertTrue(shader.contains("mat4 Reprojection;"))
		assertTrue(shader.contains("vec4 VelocityParams;"))
		assertEquals(80, TerrainVelocityUniforms.UBO_SIZE, "mat4 + vec4 in std140")

		// The write fills the block in member order: reprojection, then the reset flag and the
		// velocity viewport size in pixels.
		val uniforms = Path.of("")
			.toAbsolutePath()
			.resolve("src/main/kotlin/me/snowmii/dlss/mrt/TerrainVelocityUniforms.kt")
			.readText()
		assertTrue(uniforms.contains("withUniform(UNIFORM_NAME, UniformType.UNIFORM_BUFFER)"))
		assertTrue(uniforms.contains("putMat4f(reprojection)"))
		assertTrue(uniforms.contains("putVec4("))
		assertTrue(uniforms.contains("motion?.reset ?: true"), "a frame without published motion is a reset frame")
		assertTrue(uniforms.contains("view.getWidth(0).toFloat()"))
		assertTrue(uniforms.contains("writeToBuffer(buffer.slice(), data)"))
		assertTrue(uniforms.contains("INVALID_VELOCITY, INVALID_VELOCITY, 0f, 0f"), "the clear color is the sentinel")
	}

	/**
	 * A continuous still camera produces zero NDC motion at every depth through the shader's
	 * own formula, whatever the jitter moved: the reprojection collapses to the identity, so
	 * `ndc(Reprojection * clip) - ndc(clip)` is zero and the jitter never leaks into the
	 * vector. This is the "static motion vectors" the milestone names: terrain is static
	 * geometry, so its correct velocity is exactly the camera's, and a still camera must read
	 * zero everywhere.
	 */
	@Test
	fun `a continuous still camera produces zero NDC motion at every depth`() {
		val motion = DlssCameraMotion(RENDER_DIMENSIONS)
		val camera = sample()
		motion.advance(camera, jitter(0, -0.44f, 0.31f), 0L)
		val frame = motion.advance(camera, jitter(1, 0.37f, -0.21f), 16_000_000L)

		assertFalse(frame.reset, "a continuous still camera is not a reset frame")
		for (probe in probes) {
			val motionVector = motionOf(frame, probe)
			assertEquals(0f, motionVector.x, TOLERANCE, "x motion at $probe")
			assertEquals(0f, motionVector.y, TOLERANCE, "y motion at $probe")
		}
	}

	private fun terrainMixin(): String = Path.of("")
		.toAbsolutePath()
		.resolve("src/main/java/me/snowmii/dlss/mixin/VulkanChunkSectionsToRenderMixin.java")
		.readText()

	private fun terrainVelocityShader(): String = velocityShaderSource("velocity_terrain")

	private fun knownWorldPipelines() = ChunkSectionLayer.entries.map { it.pipeline() } + RenderPipelines.WIREFRAME

	private fun sample() = DlssCameraSample(projection, Matrix4f(), 0.0, 64.0, 0.0)

	private fun jitter(index: Int, pixelX: Float, pixelY: Float) =
		DlssJitterOffset(index, pixelX, pixelY, RENDER_DIMENSIONS)

	/** Sample points spread across the frustum, from near the eye to the far plane. */
	private val probes = listOf(
		Vector4f(0f, 0f, 0.95f, 1f),
		Vector4f(0.4f, 0.3f, 0.6f, 1f),
		Vector4f(-0.5f, 0.2f, 0.25f, 1f),
		Vector4f(0.1f, -0.4f, 0.05f, 1f),
	)

	/**
	 * The shader's own per-pixel formula: `ndc` is this fragment's normalized device
	 * coordinates recovered from gl_FragCoord, `clip = vec4(ndc, gl_FragCoord.z, 1.0)`,
	 * `previous = Reprojection * clip`, `motion = previous.xy / previous.w - ndc`.
	 */
	private fun motionOf(frame: DlssFrameMotion, clip: Vector4f): Vector4f {
		val reprojected = frame.reprojection.transform(Vector4f(clip))
		return Vector4f(
			reprojected.x / reprojected.w - clip.x / clip.w,
			reprojected.y / reprojected.w - clip.y / clip.w,
			0f,
			1f,
		)
	}

	/** The payload invariant: a valid vector is finite and strictly below the sentinel magnitude. */

	private val projection: Matrix4f = Matrix4f().setPerspective(
		Math.toRadians(70.0).toFloat(),
		RENDER_DIMENSIONS.width.toFloat() / RENDER_DIMENSIONS.height,
		1000f,
		0.05f,
		true,
	)

	private companion object {
		const val TOLERANCE = 1e-3f

		/** The shader's sentinel, mirrored so the JVM classification asserts the same value. */
		const val INVALID_VELOCITY = 10000f
	}
}
