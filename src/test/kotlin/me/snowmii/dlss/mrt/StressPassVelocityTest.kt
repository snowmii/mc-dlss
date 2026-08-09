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
	private val output = DlssDimensions(2560, 1440)
	private val render = DlssDimensions(1280, 720)

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
	 * A continuous still camera produces zero NDC motion at every depth through the shader's own
	 * formula, whatever the jitter moved: the reprojection collapses to the identity, so
	 * `ndc(Reprojection * clip) - ndc(clip)` is zero and the jitter never leaks into the vector.
	 */
	@Test
	fun `a continuous still camera produces zero NDC motion at every depth`() {
		val motion = DlssCameraMotion(render)
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

	/**
	 * Invalid and no-predecessor pixels write one representable sentinel instead of the
	 * identity-derived zero. The distinction matters: a reset frame's reprojection is the
	 * identity, which would read as "the camera stood still" - the sentinel is the only thing
	 * that tells DLSS the pixel has no predecessor at all. One value, representable in the
	 * RG16_FLOAT payload, far outside the NDC range a real vector can reach.
	 */
	@Test
	fun `invalid and no-predecessor pixels write one representable sentinel`() {
		val shader = stressShader()
		assertTrue(shader.contains("const float INVALID_VELOCITY = 10000.0;"))
		assertTrue(shader.contains("VelocityParams.x > 0.5"), "the reset flag drives the per-pixel classification")
		assertTrue(shader.contains("vec4(INVALID_VELOCITY, INVALID_VELOCITY, 0.0, 0.0)"), "every invalid path writes the one sentinel")

		// Why the flag exists: a reset frame's reprojection is the identity, so without the
		// sentinel branch the shader would report zero camera motion for a frame with no
		// predecessor. The reset flag must win over the identity-derived zero - the shader's own
		// classification turns a reset reprojection into the sentinel at every probe.
		val motion = DlssCameraMotion(render)
		val first = motion.advance(sample(), jitter(0, 0.0f, 0.0f), 0L)
		assertTrue(first.reset)
		assertEquals(Matrix4f(), first.reprojection)
		for (probe in probes) {
			val result = classify(Matrix4f(), probe, reset = true)
			assertEquals(INVALID_VELOCITY, result.x, "reset forces the sentinel at $probe")
			assertEquals(INVALID_VELOCITY, result.y, "reset forces the sentinel at $probe")
		}
	}

	/**
	 * Per-pixel invalid reprojections write the same sentinel the reset flag forces, never
	 * Inf/NaN or a mirrored finite motion, and the classification is per-pixel: one pixel whose
	 * previous w the previous camera cannot see (zero or negative) or that is non-finite, or
	 * whose reprojection produced a non-finite or out-of-range vector, does not drag the rest of
	 * the frame with it. The classification is deliberately stricter than the retained fallback
	 * compute writer's (native/mc_dlss_motion.comp: `previous.w == 0.0` before the divide): a
	 * negative w sits behind the previous camera, where the divide would mirror the point into a
	 * plausible-looking but wrong NDC. The stress pass's one representable sentinel replaces
	 * both the native writer's zero and the identity-derived zero a still camera would read as.
	 */
	@Test
	fun `per-pixel invalid reprojections write the sentinel instead of Inf or NaN`() {
		val shader = stressShader()
		val native = Path.of("").toAbsolutePath().resolve("native/mc_dlss_motion.comp").readText()

		// The classification lives in the shader, before the divide: a previous w the previous
		// camera cannot see (zero or negative - on or behind its eye plane) or that is not
		// finite (NaN/Inf) is invalid up front, and the result guard then collapses non-finite
		// and out-of-range vectors to the same sentinel instead of letting them reach the
		// RG16_FLOAT payload. The stress shader is deliberately stricter than the retained
		// fallback compute writer, which still guards only w == 0: the fallback's own repair is
		// out of this slice's scope.
		assertTrue(shader.contains("previous.w <= 0.0"), "a point on or behind the previous eye plane is invalid")
		assertTrue(native.contains("previous.w == 0.0"), "the retained fallback writer still guards only w == 0")
		assertTrue(shader.indexOf("previous.w <= 0.0") < shader.indexOf("/ previous.w"), "classification precedes the divide")
		assertTrue(shader.contains("motion.x != motion.x"), "NaN collapses to invalid")
		assertTrue(shader.contains("abs(motion.x) >= INVALID_VELOCITY"), "out-of-range collapses to invalid")

		// Per-pixel, not per-frame: a reprojection that puts one depth on the previous eye
		// plane (previous w exactly zero) invalidates only that pixel; a shallower surface that
		// the previous camera still sees stays a valid finite vector. The reprojection is
		// identity except its bottom row, which the shader's `Reprojection * clip` uses for the
		// previous w; JOML names elements mRC as column R, row C, so the bottom row is
		// (m03, m13, m23, m33) and w' = z - 0.6·w here.
		val onEyePlane = Matrix4f().m23(1f).m33(-0.6f)
		assertEquals(INVALID_VELOCITY, classify(onEyePlane, Vector4f(0f, 0f, 0.6f, 1f)).x, "w == 0 writes the sentinel")
		val visible = classify(onEyePlane, Vector4f(0f, 0f, 0.95f, 1f))
		assertNotEquals(INVALID_VELOCITY, visible.x, 0f)
		assertFiniteMotion(visible)

		// A NaN reprojection entry propagates through the multiply into the vector and collapses
		// to invalid rather than writing NaN.
		val nanReprojection = Matrix4f().set(
			Float.NaN, 0f, 0f, 0f,
			0f, 1f, 0f, 0f,
			0f, 0f, 1f, 0f,
			0f, 0f, 0f, 1f,
		)
		assertEquals(INVALID_VELOCITY, classify(nanReprojection, Vector4f(0f, 0f, 0.95f, 1f)).x)

		// An out-of-range result - magnitude at or beyond the sentinel itself - collapses to
		// invalid instead of overflowing the half-float payload or colliding with the sentinel.
		val outOfRange = Matrix4f().set(
			20000f, 0f, 0f, 0f,
			0f, 1f, 0f, 0f,
			0f, 0f, 1f, 0f,
			0f, 0f, 0f, 1f,
		)
		assertEquals(INVALID_VELOCITY, classify(outOfRange, Vector4f(0.95f, 0f, 0.6f, 1f)).x)

		// A previous w behind the camera is a point the previous camera cannot see: the divide
		// would mirror it into a plausible-looking but wrong NDC, so it writes the sentinel
		// rather than a finite motion. Same bottom-row construction as above: w' = z - 2·w puts
		// the probe behind the previous eye plane.
		val behindCamera = Matrix4f().m23(1f).m33(-2f)
		val offCenter = Vector4f(0.4f, 0.3f, 0.95f, 1f)
		assertEquals(INVALID_VELOCITY, classify(behindCamera, offCenter).x, "w < 0 (behind the previous camera) writes the sentinel")
		assertEquals(INVALID_VELOCITY, classify(behindCamera, offCenter).y, "a sentinel carries both components")

		// A non-finite previous w (an Inf reprojection entry reaching the bottom row) is invalid
		// before the divide: Inf/Inf collapses to a finite-looking value and would smuggle a
		// bogus vector past the result guard.
		val infiniteW = Matrix4f().m23(Float.POSITIVE_INFINITY)
		assertEquals(INVALID_VELOCITY, classify(infiniteW, offCenter).x, "a non-finite previous w writes the sentinel")

		// Sweep: whatever the reprojection, the classification is either the sentinel (both
		// components) or a finite vector strictly below the sentinel magnitude. Inf/NaN never
		// reaches the payload.
		for (probe in probes) {
			for (reprojection in listOf(onEyePlane, behindCamera, infiniteW, nanReprojection, outOfRange, Matrix4f())) {
				val result = classify(reprojection, probe)
				if (result.x == INVALID_VELOCITY) {
					assertEquals(INVALID_VELOCITY, result.y, "a sentinel carries both components at $probe")
				} else {
					assertFiniteMotion(result)
				}
			}
		}
	}

	/**
	 * Every production seam is bound to the assertions above: the pass selects the twin only
	 * for a velocity context and builds the two-attachment pass only then, the runtime derives
	 * the context from the open phase's velocity view and published motion (null motion or a
	 * reset frame both forcing the sentinel flag), the world-phase mixin hands the phase to the
	 * stress render on every frame - null phase included - and the phase publishes the motion
	 * only while open. A removed or broken seam fails here even when the descriptor and shader
	 * assertions stay green.
	 */
	@Test
	fun `the velocity context is wired from the open velocity-mrt phase into the stress pass`() {
		val repository = Path.of("").toAbsolutePath()
		val pass = repository.resolve("src/main/kotlin/me/snowmii/dlss/pass/StressPass.kt").readText()
		val runtime = repository.resolve("src/main/kotlin/me/snowmii/dlss/pass/StressRuntime.kt").readText()
		val mixin = repository.resolve("src/main/java/me/snowmii/dlss/mixin/LevelRendererWorldPhaseMixin.java").readText()
		val worldPhase = repository.resolve("src/main/kotlin/me/snowmii/dlss/render/WorldPhase.kt").readText()

		// The pass keeps the one-target STRESS_PIPELINE for a null context and selects the twin
		// through the same function the render path binds; the two-attachment render pass is
		// built only on the velocity route.
		assertTrue(pass.contains("velocityTwin(STRESS_PIPELINE)"))
		assertTrue(pass.contains("pipelineFor(velocity)"))
		assertTrue(pass.contains("withColorAttachment(velocity.view, Optional.empty())"))
		assertTrue(pass.contains("RenderPass.RenderArea"))
		assertTrue(pass.contains("failed = true"), "the pass failure latch-off is retained")

		// The runtime builds the context from the phase's velocity view and published motion,
		// with null motion or a reset frame forcing the sentinel flag.
		assertTrue(runtime.contains("phase?.terrainVelocityView"))
		assertTrue(runtime.contains("VelocityContext(view"))
		assertTrue(runtime.contains("motion?.reset ?: true"))

		// The mixin passes the (possibly null) phase to the stress render before ending it, so
		// vanilla sessions - phase null - still render the one-target stress pass.
		assertTrue(mixin.contains("final WorldPhase phase = ClientRuntime.active().activeWorldPhase();"))
		assertTrue(mixin.contains("StressRuntime.render(Minecraft.getInstance().gameRenderer.mainRenderTarget(), phase);"))
		assertTrue(mixin.indexOf("StressRuntime.render") < mixin.indexOf("phase.end()"))

		// The phase publishes the frame's motion only while open, mirroring the velocity view gate.
		assertTrue(worldPhase.contains("val activeMotion: DlssFrameMotion?"))
		assertTrue(worldPhase.contains("if (isOpen) runtime.activeMotion else null"))
	}

	/**
	 * Minecraft rewrites every fragment output's Location decoration to its index in the
	 * spirv-cross reflection list (`IntermediaryShaderModule.createFromSpirv`), and glslang
	 * emits outputs in first-assignment order inside `main()`. The stress shader must therefore
	 * assign the final `fragColor` before the velocity payload, exactly the ordering the
	 * live-correct `velocity_terrain.fsh` already proves; an earlier velocity write would land
	 * the near-black motion payload on attachment 0 and black the whole frame on both routes.
	 */
	@Test
	fun `the stress shader assigns velocityColor after fragColor so scene color keeps attachment 0`() {
		val shader = stressShader()
		val fragWrite = shader.indexOf("fragColor = vec4(color, 1.0);")
		val velocityWrite = shader.indexOf("velocityColor = invalidPixel")

		assertTrue(fragWrite >= 0, "the final fragColor write must exist")
		assertTrue(velocityWrite >= 0, "the velocityColor write must exist")
		assertTrue(
			velocityWrite > fragWrite,
			"velocityColor must be assigned after the final fragColor: glslang emits fragment outputs in " +
				"first-assignment order, and Minecraft rewrites locations by that reflection order, so an " +
				"earlier velocityColor write would put the motion payload on attachment 0 and black the scene",
		)

		// The live-correct control: the terrain shader that rendered normally in the same session
		// keeps the identical assignment order.
		val terrain = terrainShader()
		assertTrue(terrain.indexOf("velocityColor = invalidPixel") > terrain.indexOf("fragColor = apply_fog"),
			"the known-good terrain shader keeps the same assignment order")
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
		val spirv = compileFragmentShader(minecraftFragmentSource(stressShader()))
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

	private fun stressShader(): String = Path.of("")
		.toAbsolutePath()
		.resolve("src/main/resources/assets/mc-dlss/shaders/post/dlss_stress.fsh")
		.readText()

	private fun terrainShader(): String = Path.of("")
		.toAbsolutePath()
		.resolve("src/main/resources/assets/mc-dlss/shaders/core/velocity_terrain.fsh")
		.readText()

	/**
	 * Compiles a fragment shader exactly the way `GlslCompiler.createIntermediary` does: the
	 * global defines injected after the `#version` line, then shaderc with the Vulkan 1.2 target
	 * and automatic location/uniform mapping. Returns a copy of the SPIR-V bytes so the caller
	 * owns the buffer.
	 */
	private fun compileFragmentShader(source: String): ByteBuffer {
		val compiler = Shaderc.shaderc_compiler_initialize()
		val options = Shaderc.shaderc_compile_options_initialize()
		try {
			Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2)
			Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true)
			Shaderc.shaderc_compile_options_set_auto_map_locations(options, true)
			Shaderc.shaderc_compile_options_set_generate_debug_info(options)
			Shaderc.shaderc_compile_options_set_optimization_level(options, 0)

			MemoryStack.stackPush().use {
				val sourceBuffer = MemoryUtil.memUTF8(source, false)
				val filenameBuffer = MemoryUtil.memUTF8("dlss_stress.fsh")
				val entrypointBuffer = MemoryUtil.memUTF8("main")
				try {
					val result = Shaderc.shaderc_compile_into_spv(
						compiler, sourceBuffer, Shaderc.shaderc_fragment_shader, filenameBuffer, entrypointBuffer, options,
					)
					try {
						val status = Shaderc.shaderc_result_get_compilation_status(result)
						check(status == 0) { "shaderc failed (status $status): ${Shaderc.shaderc_result_get_error_message(result)}" }
						val compiled = checkNotNull(Shaderc.shaderc_result_get_bytes(result)) { "shaderc returned no SPIR-V bytes" }
						val copy = MemoryUtil.memCalloc(compiled.remaining())
						MemoryUtil.memCopy(compiled, copy)
						return copy
					} finally {
						Shaderc.shaderc_result_release(result)
					}
				} finally {
					MemoryUtil.memFree(entrypointBuffer)
					MemoryUtil.memFree(filenameBuffer)
					MemoryUtil.memFree(sourceBuffer)
				}
			}
		} finally {
			Shaderc.shaderc_compile_options_release(options)
			Shaderc.shaderc_compiler_release(compiler)
		}
	}

	/**
	 * The exact preprocessed source `compileShader` hands `createIntermediary`: the global
	 * defines injected right after the `#version` line. They alias vertex-only builtins and are
	 * inert for fragment output emission, but keeping them makes the compiled module match the
	 * game's byte-for-byte.
	 */
	private fun minecraftFragmentSource(source: String): String {
		val versionLineEnd = source.indexOf('\n')
		check(versionLineEnd >= 0) { "shader source must start with a #version line" }
		return source.substring(0, versionLineEnd + 1) +
			"#define gl_VertexID gl_VertexIndex\n#define gl_InstanceID gl_InstanceIndex\n#line 1 0\n" +
			source.substring(versionLineEnd + 1)
	}

	/** A stage output as spirv-cross reflects it, plus the byte offset of its Location decoration. */
	private class OutputReflection(val name: String, val locationOffset: Int, val location: Int)

	/**
	 * Reflects the stage outputs of a compiled module the way `createFromSpirv` does: parse the
	 * SPIR-V, list STAGE_OUTPUT resources, and read each output's Location decoration value and
	 * the binary word offset where that decoration lives. The list comes back in module
	 * declaration order, which is glslang's first-assignment order inside `main()`.
	 */
	private fun reflectOutputs(spirv: ByteBuffer): List<OutputReflection> {
		MemoryStack.stackPush().use { stack ->
			val contextPointer = stack.callocPointer(1)
			spvcCheck(Spvc.spvc_context_create(contextPointer), "spvc_context_create")
			val context = contextPointer.get(0)
			try {
				val intSpirv = spirv.asIntBuffer()
				val irPointer = stack.callocPointer(1)
				spvcCheck(
					Spvc.spvc_context_parse_spirv(context, intSpirv, intSpirv.remaining().toLong(), irPointer),
					"spvc_context_parse_spirv",
				)
				val compilerPointer = stack.callocPointer(1)
				spvcCheck(
					Spvc.spvc_context_create_compiler(context, 0, irPointer.get(0), 1, compilerPointer),
					"spvc_context_create_compiler",
				)
				val compiler = compilerPointer.get(0)
				val resourcesPointer = stack.callocPointer(1)
				spvcCheck(Spvc.spvc_compiler_create_shader_resources(compiler, resourcesPointer), "spvc_compiler_create_shader_resources")
				val listPointer = stack.callocPointer(1)
				val countPointer = stack.callocPointer(1)
				spvcCheck(
					Spvc.spvc_resources_get_resource_list_for_type(
						resourcesPointer.get(0), Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT, listPointer, countPointer,
					),
					"spvc_resources_get_resource_list_for_type",
				)
				val resources = SpvcReflectedResource.create(listPointer.get(0), countPointer.get(0).toInt())
				val offsetBuffer = stack.callocInt(1)
				val outputs = ArrayList<OutputReflection>(resources.capacity())
				for (index in 0 until resources.capacity()) {
					val resource = resources.get(index)
					val name = resource.nameString()
					check(Spvc.spvc_compiler_get_binary_offset_for_decoration(compiler, resource.id(), LOCATION_DECORATION, offsetBuffer)) {
						"no Location decoration on $name"
					}
					outputs.add(
						OutputReflection(
							name = name,
						locationOffset = offsetBuffer.get(0),
						location = Spvc.spvc_compiler_get_decoration(compiler, resource.id(), LOCATION_DECORATION),
					),
					)
				}
				return outputs
			} finally {
				Spvc.spvc_context_destroy(context)
			}
		}
	}

	private fun spvcCheck(result: Int, step: String) {
		check(result == Spvc.SPVC_SUCCESS) {
			val name = when (result) {
				Spvc.SPVC_ERROR_INVALID_ARGUMENT -> "SPVC_ERROR_INVALID_ARGUMENT"
				Spvc.SPVC_ERROR_OUT_OF_MEMORY -> "SPVC_ERROR_OUT_OF_MEMORY"
				Spvc.SPVC_ERROR_UNSUPPORTED_SPIRV -> "SPVC_ERROR_UNSUPPORTED_SPIRV"
				Spvc.SPVC_ERROR_INVALID_SPIRV -> "SPVC_ERROR_INVALID_SPIRV"
				else -> result.toString()
			}
			"$step failed ($name)"
		}
	}

	private fun sample() = DlssCameraSample(projection, Matrix4f(), 0.0, 64.0, 0.0)

	private fun jitter(index: Int, pixelX: Float, pixelY: Float) =
		DlssJitterOffset(index, pixelX, pixelY, render)

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

	/**
	 * The shader's full per-pixel classification, mirrored exactly: the reset flag, a previous
	 * w the previous camera cannot see (zero or negative), or a non-finite previous w (NaN/Inf)
	 * is invalid before the divide, and a non-finite or out-of-range result (magnitude at or
	 * beyond the sentinel) collapses to invalid after it. Invalid pixels write the sentinel in
	 * both components; valid pixels write the finite formula value.
	 */
	private fun classify(reprojection: Matrix4f, clip: Vector4f, reset: Boolean = false): Vector4f {
		if (reset) {
			return Vector4f(INVALID_VELOCITY, INVALID_VELOCITY, 0f, 1f)
		}
		val previous = reprojection.transform(Vector4f(clip))
		if (previous.w <= 0.0f || previous.w.isNaN() || previous.w.isInfinite()) {
			return Vector4f(INVALID_VELOCITY, INVALID_VELOCITY, 0f, 1f)
		}
		val motion = Vector4f(
			previous.x / previous.w - clip.x / clip.w,
			previous.y / previous.w - clip.y / clip.w,
			0f,
			1f,
		)
		if (motion.x != motion.x || motion.y != motion.y ||
			abs(motion.x) >= INVALID_VELOCITY || abs(motion.y) >= INVALID_VELOCITY
		) {
			return Vector4f(INVALID_VELOCITY, INVALID_VELOCITY, 0f, 1f)
		}
		return motion
	}

	/** The payload invariant: a valid vector is finite and strictly below the sentinel magnitude. */
	private fun assertFiniteMotion(motion: Vector4f) {
		assertTrue(motion.x == motion.x && motion.y == motion.y, "motion must not be NaN: $motion")
		assertTrue(abs(motion.x) < INVALID_VELOCITY && abs(motion.y) < INVALID_VELOCITY, "motion must stay below the sentinel: $motion")
	}

	private val projection: Matrix4f = Matrix4f().setPerspective(
		Math.toRadians(70.0).toFloat(),
		render.width.toFloat() / render.height,
		1000f,
		0.05f,
		true,
	)

	private fun assertVelocityTarget(target: ColorTargetState) {
		assertTrue(target.blendFunction().isEmpty())
		assertEquals(GpuFormat.RG16_FLOAT, target.format())
		assertEquals(ColorTargetState.WRITE_ALL, target.writeMask())
	}

	private class FakeTexture(format: GpuFormat, width: Int = 16, height: Int = 16) :
		GpuTexture(GpuTexture.USAGE_RENDER_ATTACHMENT, "fake", format, width, height, 1, 1) {
		override fun close() = Unit
		override fun isClosed() = false
	}

	private class FakeView(texture: GpuTexture) : GpuTextureView(texture, 0, 1) {
		override fun close() = Unit
		override fun isClosed() = false
	}

	private companion object {
		/** SPIR-V DecorationLocation, the decoration `createFromSpirv` rewrites and this suite reads back. */
		const val LOCATION_DECORATION = 30

		const val TOLERANCE = 1e-3f

		/** The shader's sentinel, mirrored so the JVM classification asserts the same value. */
		const val INVALID_VELOCITY = 10000f
	}
}
