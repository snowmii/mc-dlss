package me.snowmii.dlss.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.systems.RenderPassDescriptor
import java.util.Optional
import org.lwjgl.system.MemoryUtil
import me.snowmii.dlss.pass.StressPass
import net.minecraft.resources.Identifier
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * While an open VELOCITY_MRT world phase offers its RG16_FLOAT velocity view, the stress
 * pass binds a two-target twin of its own pipeline and writes jitter-free NDC camera motion
 * derived from the published reprojection and the reversed-Z depth; on vanilla or camera-only
 * frames it keeps the one-target pipeline.
 *
 * The stress fragment shader is compiled through the same LWJGL Shaderc + spirv-cross path
 * `GlslCompiler` and `IntermediaryShaderModule` use, and the reflected output order is pinned
 * to fragColor-then-velocityColor - the order Minecraft's location rewrite turns into color
 * attachments 0 and 1. The two-target twin and the two-attachment render pass must agree on
 * count and format, because that is the one check `RenderPass.setPipeline` performs on first
 * bind. The sentinel choice is pinned here as shader source; the still-camera invariant and
 * the per-pixel collapse of invalid reprojections live on `DlssCameraMotion`.
 */
class StressPassVelocityTest {

	/**
	 * The one-target stress pipeline is the identity of the vanilla and camera-only routes:
	 * the pass never selects a twin without a velocity context, never rebuilds or mutates the
	 * one-target pipeline, and its single target agrees with the one-attachment render pass
	 * that binds it.
	 */
	@Test
	fun `vanilla and camera-only stress rendering keeps the one-target pipeline`() {
		val pipeline = StressPass.pipelineForVelocityRoute(null)

		assertEquals(Identifier.fromNamespaceAndPath("mc-dlss", "pipeline/dlss_stress"), pipeline.location)
		assertEquals(1, pipeline.colorTargetStates.size)
		assertEquals(GpuFormat.RGBA8_UNORM, pipeline.colorTargetStates[0]!!.format())
		assertSame(pipeline, StressPass.pipelineForVelocityRoute(null), "the one-target pipeline is never rebuilt or mutated")

		val descriptor = RenderPassDescriptor.create { "DLSS stress" }
			.withColorAttachment(FakeView(FakeTexture(GpuFormat.RGBA8_UNORM)))
		assertEquals(1, descriptor.colorAttachments().size)
		assertEquals(1, pipeline.colorTargetStates.size)
	}

	/**
	 * A velocity context selects a two-target twin at a distinct mc-dlss location that preserves
	 * the source shaders and target zero, adds exactly the unblended RG16_FLOAT velocity target
	 * at index 1, and is cached per source pipeline.
	 */
	@Test
	fun `the velocity route binds a two-target twin with the unblended RG16 velocity target`() {
		val context = VelocityContext(FakeView(FakeTexture(GpuFormat.RG16_FLOAT)), Matrix4f(), reset = false)
		val vanilla = StressPass.pipelineForVelocityRoute(null)
		val twin = StressPass.pipelineForVelocityRoute(context)

		assertNotSame(vanilla, twin)
		assertSame(vanilla, StressPass.pipelineForVelocityRoute(null), "selecting a twin must not replace the one-target pipeline")
		assertEquals(Identifier.fromNamespaceAndPath("mc-dlss", "velocity/pipeline/dlss_stress"), twin.location)

		assertSame(vanilla.vertexShader, twin.vertexShader)
		assertSame(vanilla.fragmentShader, twin.fragmentShader)
		assertEquals(vanilla.bindGroupLayouts.size, twin.bindGroupLayouts.size)

		assertEquals(2, twin.colorTargetStates.size)
		assertSame(vanilla.colorTargetStates[0], twin.colorTargetStates[0])
		assertVelocityTarget(twin.colorTargetStates[1]!!)

		// One cached twin per source pipeline, so the first velocity frame pays the compile
		// once and every later frame hits the lazy-compile cache.
		assertSame(twin, StressPass.pipelineForVelocityRoute(context))
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

		val oneTarget = RenderPassDescriptor.create { "DLSS stress" }.withColorAttachment(scene)
		val twoTarget = RenderPassDescriptor.create { "DLSS stress velocity" }
			.withColorAttachment(scene)
			.withColorAttachment(velocity, Optional.empty())

		val vanillaPipeline = StressPass.pipelineForVelocityRoute(null)
		val twin = StressPass.pipelineForVelocityRoute(VelocityContext(velocity, Matrix4f(), reset = false))

		assertEquals(1, oneTarget.colorAttachments().size)
		assertEquals(vanillaPipeline.colorTargetStates.size, oneTarget.colorAttachments().size)

		val attachments = twoTarget.colorAttachments()
		assertEquals(2, attachments.size)
		assertEquals(twin.colorTargetStates.size, attachments.size)
		assertSame(scene, attachments[0]!!.textureView())
		assertSame(velocity, attachments[1]!!.textureView())
		assertTrue(attachments[1]!!.clearValue().isEmpty, "the velocity attachment is never cleared")
		assertEquals(GpuFormat.RG16_FLOAT, attachments[1]!!.textureView().texture().format)
		assertEquals(twin.colorTargetStates[1]!!.format(), attachments[1]!!.textureView().texture().format)
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
	 * The compiled seam: the shader is compiled through the same LWJGL Shaderc + spirv-cross
	 * path `GlslCompiler.createIntermediary` and `IntermediaryShaderModule.createFromSpirv`
	 * use. The stage-output reflection list must come back fragColor-first (that list's index
	 * is what the location rewrite writes), and applying the rewrite must leave fragColor on
	 * Location 0 (the scene attachment) and velocityColor on Location 1 (the velocity
	 * attachment).
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
			outputs.forEachIndexed { index, output -> intSpirv.put(output.locationOffset, index) }
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
}
