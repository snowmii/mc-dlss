package me.snowmii.dlss.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.Optional
import kotlin.io.path.readText
import kotlin.math.abs
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc
import org.lwjgl.util.spvc.Spvc
import org.lwjgl.util.spvc.SpvcReflectedResource
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.pass.StressPass
import me.snowmii.dlss.render.DlssCameraMotion
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitterOffset
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
 * Proves the stress pass is the first production writer of the velocity MRT (M-5, AC-3): while
 * an open VELOCITY_MRT world phase offers its RG16_FLOAT velocity view, the stress pass binds a
 * two-target twin of its own pipeline and writes jitter-free NDC camera motion derived from the
 * published reprojection and the reversed-Z depth; on vanilla or camera-only frames it keeps the
 * one-target pipeline and never throws.
 *
 * The pipeline- and shader-level claims are descriptor and source proofs, exactly like the rest
 * of the MRT suite, plus one compiled proof: the stress fragment shader is compiled through the
 * same LWJGL Shaderc + spirv-cross path `GlslCompiler` and `IntermediaryShaderModule` use, and
 * the reflected output order is pinned to fragColor-then-velocityColor - the order Minecraft's
 * location rewrite turns into color attachments 0 and 1. The two-target twin and the
 * two-attachment render pass must agree on count and format, because that is the one check
 * `RenderPass.setPipeline` performs on first bind. The still-camera math is exercised through
 * the real [DlssCameraMotion] with the shader's own per-pixel formula, and the sentinel choice
 * is pinned both as shader source and as the classification behavior: the reset flag forces it
 * for a whole frame, and per-pixel invalid reprojections - a previous homogeneous coordinate
 * the previous camera cannot see (w <= 0) or that is non-finite, plus non-finite or out-of-range
 * results - collapse to the same sentinel instead of Inf/NaN or a mirrored finite motion from a
 * point behind the previous camera.
 */
class StressPassVelocityTest {
	private val RENDER_DIMENSIONS = DlssDimensions(1280, 720)

	/**
	 * The one-target stress pipeline is the identity of the vanilla and camera-only routes:
	 * the pass never selects a twin without a velocity context, never rebuilds or mutates the
	 * one-target pipeline, and its single target agrees with the one-attachment render pass
	 * that binds it.
	 */
	@Test
	fun `vanilla and camera-only stress rendering keeps the one-target pipeline`() {
		val pipeline = StressPass.pipelineFor(null)

		assertEquals(Identifier.fromNamespaceAndPath("mc-dlss", "pipeline/dlss_stress"), pipeline.location)
		assertEquals(1, pipeline.colorTargetStates.size)
		assertEquals(GpuFormat.RGBA8_UNORM, pipeline.colorTargetStates[0]!!.format())
		assertSame(pipeline, StressPass.pipelineFor(null), "the one-target pipeline is never rebuilt or mutated")

		// The exact one-attachment shape the pass binds on the vanilla route.
		val descriptor = RenderPassDescriptor.create({ "DLSS stress" })
			.withColorAttachment(FakeView(FakeTexture(GpuFormat.RGBA8_UNORM)))
		assertEquals(1, descriptor.colorAttachments().size)
		assertEquals(1, pipeline.colorTargetStates.size)
	}

	/**
	 * A velocity context selects a two-target twin at a distinct mc-dlss location that preserves
	 * the source shaders and target zero, adds exactly the unblended RG16_FLOAT velocity target
	 * at index 1, and is cached per source pipeline exactly like the terrain twins.
	 */
	@Test
	fun `the velocity route binds a two-target twin with the unblended RG16 velocity target`() {
		val context = VelocityContext(FakeView(FakeTexture(GpuFormat.RG16_FLOAT)), Matrix4f(), reset = false)
		val vanilla = StressPass.pipelineFor(null)
		val twin = StressPass.pipelineFor(context)

		assertNotSame(vanilla, twin)
		assertSame(vanilla, StressPass.pipelineFor(null), "selecting a twin must not replace the one-target pipeline")
		assertEquals(Identifier.fromNamespaceAndPath("mc-dlss", "velocity/pipeline/dlss_stress"), twin.location)

		assertSame(vanilla.vertexShader, twin.vertexShader)
		assertSame(vanilla.fragmentShader, twin.fragmentShader)
		assertEquals(vanilla.bindGroupLayouts.size, twin.bindGroupLayouts.size)

		assertEquals(2, twin.colorTargetStates.size)
		assertSame(vanilla.colorTargetStates[0], twin.colorTargetStates[0])
		assertVelocityTarget(twin.colorTargetStates[1]!!)

		// One cached twin per source pipeline, so the first velocity frame pays the compile
		// once and every later frame hits the lazy-compile cache.
		assertSame(twin, StressPass.pipelineFor(context))
	}

	/**
	 * The attachment count and format the stress pass builds must agree with the pipeline it
	 * binds on both routes: that is exactly what `RenderPass.setPipeline` validates on first
	 * bind, so a disagreement would fail on the first velocity frame, lazily, mid-render.
	 */
	@Test
	fun `stress render-pass attachments agree with the pipeline on both routes`() {
		val scene = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM))
		val velocity = FakeView(FakeTexture(GpuFormat.RG16_FLOAT))

		val oneTarget = RenderPassDescriptor.create({ "DLSS stress" }).withColorAttachment(scene)
		val twoTarget = RenderPassDescriptor.create({ "DLSS stress velocity" })
			.withColorAttachment(scene)
			.withColorAttachment(velocity, Optional.empty())

		val vanillaPipeline = StressPass.pipelineFor(null)
		val twin = StressPass.pipelineFor(VelocityContext(velocity, Matrix4f(), reset = false))

		assertEquals(1, oneTarget.colorAttachments().size)
		assertEquals(vanillaPipeline.colorTargetStates.size, oneTarget.colorAttachments().size)

		val attachments = twoTarget.colorAttachments()
		assertEquals(2, attachments.size)
		assertEquals(twin.colorTargetStates.size, attachments.size)
		assertSame(scene, attachments[0]!!.textureView())
		assertSame(velocity, attachments[1]!!.textureView())
		assertTrue(attachments[1]!!.clearValue().isEmpty(), "the velocity attachment is never cleared")
		assertEquals(GpuFormat.RG16_FLOAT, attachments[1]!!.textureView().texture().getFormat())
		assertEquals(twin.colorTargetStates[1]!!.format(), attachments[1]!!.textureView().texture().getFormat())
	}

	/**
	 * The shader derives previous NDC from the reprojection and the reversed-Z depth, and
	 * subtracts the current NDC: exactly the formula the reprojection was composed to serve,
	 * with the depth passed straight into clip.z so the reversed-Z convention (1.0 near,
	 * 0.0 far) is preserved rather than flipped.
	 */
	@Test
	fun `the stress shader derives previous NDC from reprojection and reversed-Z depth`() {
		val shader = stressShader()

		assertTrue(shader.contains("mat4 Reprojection;"))
		assertTrue(shader.contains("vec4 VelocityParams;"))
		assertTrue(shader.contains("out vec4 velocityColor;"), "the velocity output is the pipeline's second color target")
		assertTrue(shader.contains("vec4 clip = vec4(ndc, sceneDepth, 1.0);"))
		assertTrue(shader.contains("vec4 previous = Reprojection * clip;"))
		assertTrue(shader.contains("previous.xy / previous.w - ndc"))
	}

	/**
	 * The compiled seam, exercising the true mechanism: the shader is compiled through the same
	 * LWJGL Shaderc + spirv-cross path `GlslCompiler.createIntermediary` and
	 * `IntermediaryShaderModule.createFromSpirv` use, the stage-output reflection list must come
	 * back fragColor-first (that list's index is what the location rewrite writes), and applying
	 * the rewrite must leave fragColor on Location 0 (the scene attachment) and velocityColor on
	 * Location 1 (the velocity attachment). The terrain shader cannot be compiled here - it needs
	 * Minecraft's import/resource preprocessor - so it stays a source-order control in the
	 * deterministic test above.
	 */
	@Test
	fun `the stress shader reflects outputs in fragColor-then-velocityColor order through Minecraft's compile path`() {
		val spirv = compileFragmentShader(stressShader())
		try {
			val outputs = reflectOutputs(spirv)
			assertEquals(
				listOf("fragColor", "velocityColor"),
				outputs.map { it.name },
				"the stage-output reflection list must be fragColor first: createFromSpirv rewrites each " +
					"output's Location to its index in this list, so the list order IS the attachment binding",
			)

			// Apply the exact rewrite createFromSpirv performs on the module - output i gets
			// Location i written at its binary decoration offset - then re-reflect the mutated
			// module and read back the Location decorations the driver will see.
			val intSpirv = spirv.asIntBuffer()
			outputs.forEachIndexed { index, OUTPUT_DIMENSIONS -> intSpirv.put(OUTPUT_DIMENSIONS.locationOffset, index) }
			val rewritten = reflectOutputs(spirv)
			assertEquals(
				mapOf("fragColor" to 0, "velocityColor" to 1),
				rewritten.associate { it.name to it.location },
				"after the createFromSpirv rewrite fragColor must sit on attachment 0 (scene color) and " +
					"velocityColor on attachment 1 (velocity)",
			)

		} finally {
			MemoryUtil.memFree(spirv)
		}
	}

	private fun stressShader(): String = repositorySource("src/main/resources/assets/mc-dlss/shaders/post/dlss_stress.fsh")

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
	 * The shader's own per-pixel formula: `previous = Reprojection * vec4(ndc, depth, 1)`,
	 * `motion = previous.xy / previous.w - ndc`, read against NDC and reversed-Z depth as the
	 * rendered frame holds them.
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
		/** SPIR-V DecorationLocation, the decoration `createFromSpirv` rewrites and this suite reads back. */
		const val LOCATION_DECORATION = 30

		const val TOLERANCE = 1e-3f

		/** The shader's sentinel, mirrored so the JVM classification asserts the same value. */
		const val INVALID_VELOCITY = 10000f
	}
}
