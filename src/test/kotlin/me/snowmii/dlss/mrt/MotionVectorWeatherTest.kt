package me.snowmii.dlss.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.Optional
import java.util.OptionalDouble
import java.util.function.Supplier
import kotlin.io.path.readText
import kotlin.math.abs
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.SceneTarget
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.WeatherEffectRenderer
import net.minecraft.client.renderer.state.level.WeatherRenderState
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector4f
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc
import org.lwjgl.util.spvc.Spvc
import org.lwjgl.util.spvc.SpvcReflectedResource
import org.spongepowered.asm.mixin.injection.Redirect
import com.google.gson.JsonParser

/**
 * Weather vertical proof for M-6's velocity writer.
 *
 * `WeatherEffectRenderer.render` is the smallest remaining bespoke world pass: it creates one
 * pass over the weather target with the `WEATHER_DEPTH_WRITE` or `WEATHER_NO_DEPTH_WRITE`
 * pipeline and draws the CPU-baked rain and snow columns. This slice redirects only that pass
 * creation while an open VELOCITY_MRT world phase offers the scene velocity view: the pass gets
 * the scene-sized RG16_FLOAT velocity attachment at color index 1, the pipeline-boundary seam
 * swaps in a cached weather twin that preserves the source weather descriptors (translucent
 * blended target zero, particle vertex format, quads, no cull, the depth variant's own write
 * state) plus the mc-dlss weather velocity fragment shader and the existing VelocityConfig
 * layout, and the writer fills the shared VelocityConfig payload with this frame's jitter-
 * stripped camera reprojection - the exact invalid sentinel on a reset frame - on the same
 * command encoder the pass is created from. Vanilla and CAMERA_ONLY routes keep the exact
 * source pass: the control seam answers false and the redirect falls through to the vanilla
 * one-attachment creation, never throwing.
 *
 * The test JVM does not apply Fabric mixins or own a live Blaze3D device, so this suite makes
 * no live transformed/GPU draw claim: descriptors are proven against the mapped 26.2 classes,
 * the control seam is driven at the same seams the mixin uses, and passthrough is proven by
 * the control seam answering false (vanilla keeps control). The weather shader compiles through
 * the same LWJGL Shaderc + spirv-cross path `GlslCompiler` and `IntermediaryShaderModule` use -
 * it inlines the two vanilla includes it needs, so unlike the terrain shader it is
 * self-contained - and the reflected output order is pinned to fragColor-then-velocityColor,
 * the order Minecraft's location rewrite turns into color attachments 0 and 1.
 */
class MotionVectorWeatherTest {
	private val repository = Path.of("").toAbsolutePath()

	@Test
	fun `weather pipelines are the mapped depth variants and the twin preserves source descriptors`() {
		// The two mapped weather pipelines: same particle snippet, differing only in depth write.
		val depthWrite = RenderPipelines.WEATHER_DEPTH_WRITE
		val noDepthWrite = RenderPipelines.WEATHER_NO_DEPTH_WRITE
		assertNotSame(depthWrite, noDepthWrite)
		assertSame(depthWrite.vertexShader, noDepthWrite.vertexShader)
		assertSame(depthWrite.fragmentShader, noDepthWrite.fragmentShader)
		assertTrue(depthWrite.depthStencilState!!.writeDepth, "WEATHER_DEPTH_WRITE writes depth")
		assertFalse(noDepthWrite.depthStencilState!!.writeDepth, "WEATHER_NO_DEPTH_WRITE reads depth only")

		// The plain twin (M-4 descriptor contract) and the weather writer twin on top of it.
		val plain = velocityTwin(depthWrite)
		val twin = weatherVelocityTwin(plain)

		// The writer twin preserves every source descriptor field through the plain twin.
		assertSame(depthWrite.vertexShader, twin.vertexShader)
		assertEquals(depthWrite.shaderDefines, twin.shaderDefines)
		assertSame(depthWrite.depthStencilState, twin.depthStencilState)
		assertSame(depthWrite.polygonMode, twin.polygonMode)
		assertEquals(depthWrite.isCull, twin.isCull)
		assertSame(depthWrite.primitiveTopology, twin.primitiveTopology)
		assertSame(depthWrite.getVertexFormatBinding(0), twin.getVertexFormatBinding(0))
		assertSame(DefaultVertexFormat.PARTICLE, twin.getVertexFormatBinding(0))

		// The source weather layouts stay, exactly one VelocityConfig layout is added.
		assertEquals(depthWrite.bindGroupLayouts.size + 1, twin.bindGroupLayouts.size)
		for (index in depthWrite.bindGroupLayouts.indices) {
			assertSame(depthWrite.bindGroupLayouts[index], twin.bindGroupLayouts[index])
		}
		assertSame(TerrainVelocityUniforms.LAYOUT, twin.bindGroupLayouts.last())

		// Target zero is the source weather target - translucent blend intact - and target one
		// is exactly the unblended RG16_FLOAT velocity payload.
		val sourceTargets = depthWrite.colorTargetStates
		val twinTargets = twin.colorTargetStates
		assertEquals(2, twinTargets.size)
		assertSame(sourceTargets[0], twinTargets[0])
		assertEquals(Optional.of(BlendFunction.TRANSLUCENT), twinTargets[0]!!.blendFunction())
		assertVelocityTarget(twinTargets[1]!!)
	}

	@Test
	fun `weather twin is cached per source and distinct from terrain and entity twins`() {
		val source = RenderPipelines.WEATHER_DEPTH_WRITE
		val plain = velocityTwin(source)

		assertEquals(Identifier.fromNamespaceAndPath("mc-dlss", "velocity/pipeline/weather_depth_write"), plain.location)
		assertSame(plain, velocityTwin(source), "the plain twin is cached per source pipeline")

		val twin = weatherVelocityTwin(plain)
		assertSame(twin, weatherVelocityTwin(plain), "the writer twin is cached per plain twin")
		assertEquals(Identifier.fromNamespaceAndPath("mc-dlss", "velocity/weather/weather_depth_write"), twin.location)

		// The weather twin lives at its own location: no collision with the plain twin's
		// velocity/pipeline path or the terrain/entity writer twins of the same source.
		assertNotEquals(plain.location, twin.location)
		assertNotEquals(terrainVelocityTwin(plain).location, twin.location)
		assertNotEquals(entityVelocityTwin(plain).location, twin.location)
	}

	@Test
	fun `weather twin keeps the no-depth-write variant's read-only depth state`() {
		val twin = weatherVelocityTwin(velocityTwin(RenderPipelines.WEATHER_NO_DEPTH_WRITE))
		assertSame(RenderPipelines.WEATHER_NO_DEPTH_WRITE.depthStencilState, twin.depthStencilState)
		assertFalse(twin.depthStencilState!!.writeDepth, "the no-depth-write variant must keep reading depth only")
	}

	@Test
	fun `weather pass attachments agree with the twin on both routes`() {
		val scene = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM))
		val velocity = FakeView(FakeTexture(GpuFormat.RG16_FLOAT))

		// The vanilla route: the one-attachment weather pass binds the source pipeline.
		val oneTarget = RenderPassDescriptor.create({ "Weather Effect" })
			.withColorAttachment(scene)
			.withDepthAttachment(FakeView(FakeTexture(GpuFormat.D32_FLOAT)), OptionalDouble.empty())
		assertEquals(1, oneTarget.colorAttachments().size)
		assertEquals(RenderPipelines.WEATHER_DEPTH_WRITE.colorTargetStates.size, oneTarget.colorAttachments().size)

		// The VELOCITY_MRT route: the two-attachment pass must agree with the two-target twin -
		// exactly the count/format check RenderPass.setPipeline performs on first bind.
		val twin = weatherVelocityTwin(velocityTwin(RenderPipelines.WEATHER_DEPTH_WRITE))
		val twoTarget = RenderPassDescriptor.create({ "Weather Effect velocity" })
			.withColorAttachment(scene)
			.withColorAttachment(velocity, Optional.empty())
			.withDepthAttachment(FakeView(FakeTexture(GpuFormat.D32_FLOAT)), OptionalDouble.empty())

		val attachments = twoTarget.colorAttachments()
		assertEquals(2, attachments.size)
		assertEquals(twin.colorTargetStates.size, attachments.size)
		assertSame(scene, attachments[0]!!.textureView())
		assertSame(velocity, attachments[1]!!.textureView())
		assertTrue(attachments[1]!!.clearValue().isEmpty(), "the velocity attachment is never cleared")
		assertEquals(GpuFormat.RG16_FLOAT, attachments[1]!!.textureView().texture().getFormat())
		assertEquals(twin.colorTargetStates[1]!!.format(), attachments[1]!!.textureView().texture().getFormat())
	}

	@Test
	fun `weather shader preserves the particle color output and writes velocity after the final color`() {
		val shader = weatherShader()

		// The vanilla core/particle fragment body the weather pipelines bind, verbatim.
		assertTrue(shader.contains("texture(Sampler0, texCoord0) * vertexColor * ColorModulator"))
		assertTrue(shader.contains("color.a < 0.1"))
		assertTrue(shader.contains("discard"))
		assertTrue(shader.contains("fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor)"))

		// The inlined vanilla includes it needs: fog and dynamic transforms (ColorModulator).
		assertTrue(shader.contains("layout(std140) uniform Fog {"))
		assertTrue(shader.contains("layout(std140) uniform DynamicTransforms {"))
		assertTrue(shader.contains("vec4 ColorModulator;"))
		assertTrue(shader.contains("uniform sampler2D Sampler0;"))

		// The payload: the same VelocityConfig block and velocity output the terrain writer uses.
		assertTrue(shader.contains("layout(std140) uniform VelocityConfig {"))
		assertTrue(shader.contains("mat4 Reprojection;"))
		assertTrue(shader.contains("vec4 VelocityParams;"))
		assertTrue(shader.contains("out vec4 velocityColor;"))
		assertTrue(shader.contains("const float INVALID_VELOCITY = 10000.0;"))

		// Assignment order: glslang emits fragment outputs in first-assignment order and
		// Minecraft rewrites locations by that reflection order, so the final fragColor write
		// must precede the velocityColor write or the near-black payload lands on attachment 0.
		val fragWrite = shader.indexOf("fragColor = apply_fog")
		val velocityWrite = shader.indexOf("velocityColor = invalidPixel")
		assertTrue(fragWrite >= 0, "the final fragColor write must exist")
		assertTrue(velocityWrite >= 0, "the velocityColor write must exist")
		assertTrue(velocityWrite > fragWrite, "velocityColor must be assigned after the final fragColor")
	}

	/**
	 * The compiled seam, exercising the true mechanism: the weather shader is self-contained
	 * (it inlines the two vanilla includes instead of #moj_import, which needs Minecraft's
	 * resource preprocessor), so it can be compiled through the same LWJGL Shaderc + spirv-cross
	 * path `GlslCompiler.createIntermediary` and `IntermediaryShaderModule.createFromSpirv` use.
	 * The stage-output reflection list must come back fragColor-first (that list's index is what
	 * the location rewrite writes), and applying the rewrite must leave fragColor on Location 0
	 * (the weather target) and velocityColor on Location 1 (the velocity attachment).
	 */
	@Test
	fun `weather shader reflects outputs in fragColor-then-velocityColor order through Minecraft's compile path`() {
		val spirv = compileFragmentShader(minecraftFragmentSource(weatherShader()))
		try {
			val outputs = reflectOutputs(spirv)
			assertEquals(
				listOf("fragColor", "velocityColor"),
				outputs.map { it.name },
				"the stage-output reflection list must be fragColor first: createFromSpirv rewrites each " +
					"output's Location to its index in this list, so the list order IS the attachment binding",
			)

			val intSpirv = spirv.asIntBuffer()
			outputs.forEachIndexed { index, output -> intSpirv.put(output.locationOffset, index) }
			val rewritten = reflectOutputs(spirv)
			assertEquals(
				mapOf("fragColor" to 0, "velocityColor" to 1),
				rewritten.associate { it.name to it.location },
				"after the createFromSpirv rewrite fragColor must sit on attachment 0 (weather color) and " +
					"velocityColor on attachment 1 (velocity)",
			)
		} finally {
			MemoryUtil.memFree(spirv)
		}
	}

	/**
	 * The weather shader derives previous NDC from the same jitter-stripped camera reprojection
	 * the terrain writer uses, and the shared classification collapses reset frames and invalid
	 * reprojections to the one representable sentinel instead of the identity-derived zero.
	 */
	@Test
	fun `weather shader writes jitter-stripped camera reprojection with the exact sentinel on reset`() {
		val shader = weatherShader()
		assertTrue(shader.contains("vec4 clip = vec4(ndc, gl_FragCoord.z, 1.0);"))
		assertTrue(shader.contains("vec4 previous = Reprojection * clip;"))
		assertTrue(shader.contains("previous.xy / previous.w - ndc"))
		assertTrue(shader.contains("VelocityParams.x > 0.5"), "the reset flag drives the per-pixel classification")
		assertTrue(shader.contains("vec4(INVALID_VELOCITY, INVALID_VELOCITY, 0.0, 0.0)"), "every invalid path writes the one sentinel")

		// The classification behavior: a reset frame (identity reprojection) must force the
		// sentinel at every probe - the identity would otherwise read as a still camera - and a
		// valid continuous reprojection produces a finite vector strictly below the sentinel.
		for (probe in probes) {
			val reset = classify(Matrix4f(), probe, reset = true)
			assertEquals(INVALID_VELOCITY, reset.x, "reset forces the sentinel at $probe")
			assertEquals(INVALID_VELOCITY, reset.y, "reset forces the sentinel at $probe")
		}

		val camera = DlssFrameMotion(Matrix4f(), 1f, 1f, 16f, false)
		for (probe in probes) {
			val result = classify(camera.reprojection, probe)
			if (result.x == INVALID_VELOCITY) {
				assertEquals(INVALID_VELOCITY, result.y, "a sentinel carries both components at $probe")
			} else {
				assertTrue(result.x == result.x && result.y == result.y, "motion must not be NaN: $result")
				assertTrue(
					abs(result.x) < INVALID_VELOCITY && abs(result.y) < INVALID_VELOCITY,
					"a valid vector stays below the sentinel: $result",
				)
			}
		}
	}

	@Test
	fun `an eligible open velocity-mrt phase offers the weather writer the scene velocity view`() {
		val phase = phase(velocityRuntime())

		// Closed or not yet opened: no velocity view, the weather pass stays vanilla.
		assertFalse(WeatherVelocityRender.canRedirect(phase))
		assertNull(WeatherVelocityRender.velocityView(phase))

		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		assertTrue(WeatherVelocityRender.canRedirect(phase))
		val view = checkNotNull(WeatherVelocityRender.velocityView(phase))
		assertEquals(GpuFormat.RG16_FLOAT, view.texture().getFormat())
		assertEquals(render.width, view.getWidth(0))
		assertEquals(render.height, view.getHeight(0))

		phase.end()
		assertFalse(WeatherVelocityRender.canRedirect(phase))
		assertNull(WeatherVelocityRender.velocityView(phase))
	}

	@Test
	fun `vanilla camera-only and non-open phases keep the weather pass vanilla and cannot throw`() {
		// Camera-only: the first foreign pipeline latches the fallback route, so the open phase
		// offers no velocity view and the writer answers false - the exact source pass survives.
		val cameraOnly = velocityRuntime()
		cameraOnly.observeWorldPipeline(
			MotionVectorPipeline(
				"example:pipeline/waving_terrain",
				listOf(MotionVectorShader("example:core/waving_terrain", "example")),
			),
		)
		assertEquals(MotionVectorRoute.CAMERA_ONLY, cameraOnly.motionVectorRoute)
		val cameraOnlyPhase = phase(cameraOnly)
		assertDoesNotThrow {
			cameraOnlyPhase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
			assertFalse(WeatherVelocityRender.canRedirect(cameraOnlyPhase))
			assertNull(WeatherVelocityRender.velocityView(cameraOnlyPhase))
			cameraOnlyPhase.end()
		}

		// Vanilla: a session without DLSS keeps the weather pass on its exact source route.
		val vanillaSession = DlssSession(
			DlssStartupConfig(
				enabled = false,
				qualityMode = SRMode.QUALITY,
				outputDimensions = output,
				sdkPath = null,
				nativeLibraryPath = null,
				dataPath = null,
				warnings = emptyList(),
			),
		)
		val vanillaPhase = phase(
			RenderRuntime(
				session = vanillaSession,
				sceneTarget = SceneTarget(
					allocate = { width, height -> FakeTarget(width, height) },
					release = { (it as FakeTarget).releases++ },
					allocateVelocity = { _, _ -> null },
				),
				startup = { null },
			),
		)
		assertDoesNotThrow {
			vanillaPhase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
			assertFalse(WeatherVelocityRender.canRedirect(vanillaPhase))
			vanillaPhase.end()
		}
	}

	@Test
	fun `weather writer fills the existing VelocityConfig payload with camera motion and reset sentinel`() {
		val writer = source("src/main/kotlin/me/snowmii/dlss/mrt/WeatherVelocityRender.kt")
		val uniforms = source("src/main/kotlin/me/snowmii/dlss/mrt/TerrainVelocityUniforms.kt")

		// The writer delegates to the existing terrain payload writer: same block, same buffer
		// layout, same sentinel. The frame's camera motion and reset flag are the phase's.
		assertTrue(writer.contains("TerrainVelocityUniforms.writeFrame"))
		assertTrue(writer.contains("phase.activeMotion"))
		assertTrue(writer.contains("UNIFORM_NAME"))
		assertTrue(writer.contains("canRedirect"), "the control seam answers false for ineligible routes")

		// The payload itself is the existing VelocityConfig block: the reset flag forces the
		// exact invalid sentinel, and the reprojection is the frame's jitter-stripped camera one.
		assertTrue(uniforms.contains("motion?.reset ?: true"), "a frame without published motion is a reset frame")
		assertTrue(uniforms.contains("INVALID_VELOCITY = 10000.0f"), "the one representable sentinel value")
		assertEquals("VelocityConfig", TerrainVelocityUniforms.UNIFORM_NAME)
		assertEquals(10000f, TerrainVelocityUniforms.INVALID_VELOCITY)
		assertSame(TerrainVelocityUniforms.LAYOUT, WeatherVelocityRender.LAYOUT, "the weather twin reuses the existing VelocityConfig layout")
		assertEquals(TerrainVelocityUniforms.UNIFORM_NAME, WeatherVelocityRender.UNIFORM_NAME)
		assertEquals("core/velocity_weather", WeatherVelocityRender.SHADER_PATH)
		assertEquals(Identifier.fromNamespaceAndPath("mc-dlss", "core/velocity_weather"), WeatherVelocityRender.FRAGMENT_SHADER)
	}

	@Test
	fun `weather mixin redirects the mapped weather pass creation and pipeline binding seams`() {
		val mixin = source("src/main/java/me/snowmii/dlss/mixin/WeatherEffectRendererMotionMixin.java")
		val mixins = source("src/main/resources/mc-dlss.mixins.json")

		// The mapped seam: WeatherEffectRenderer.render creates the weather pass and binds the
		// WEATHER_DEPTH_WRITE / WEATHER_NO_DEPTH_WRITE selection into it.
		assertTrue(mixin.contains("@Mixin(WeatherEffectRenderer.class)"))
		assertTrue(mixin.contains("method = \"render\""))
		assertTrue(mixin.contains("CommandEncoder;createRenderPass("))
		assertTrue(mixin.contains("RenderPass;setPipeline("))
		assertTrue(mixin.contains("withColorAttachment(velocity, Optional.empty())"))
		assertTrue(mixin.contains("weatherVelocityTwin(velocityTwin(pipeline))"))
		// The writer fills the payload on the same encoder the pass is created from.
		assertTrue(mixin.contains("writeFrame(encoder"))
		// Ineligible routes keep the exact vanilla pass creation and cannot throw.
		assertTrue(mixin.contains("getTerrainVelocityView()"))
		assertTrue(mixin.contains("encoder.createRenderPass(label, colorTexture, clearColor, depthTexture, clearDepth)"))
		assertTrue(mixins.contains("WeatherEffectRendererMotionMixin"))

		// The mapped render method exists with the descriptor the redirect lives in.
		val render = WeatherEffectRenderer::class.java.getDeclaredMethod("render", Vec3::class.java, WeatherRenderState::class.java)
		assertEquals(Void.TYPE, render.returnType)

		// The pass-creation handler's @Redirect matches the mapped CommandEncoder descriptor.
		val mixinClass = Class.forName("me.snowmii.dlss.mixin.WeatherEffectRendererMotionMixin")
		val passHandler = mixinClass.getDeclaredMethod(
			"mcDlssWeatherRenderPass",
			CommandEncoder::class.java,
			Supplier::class.java,
			GpuTextureView::class.java,
			Optional::class.java,
			GpuTextureView::class.java,
			OptionalDouble::class.java,
		)
		val passRedirect = requireNotNull(passHandler.getAnnotation(Redirect::class.java))
		assertTrue(passRedirect.method.contentEquals(arrayOf("render")))
		assertEquals("INVOKE", passRedirect.at.value)
		assertEquals(
			"Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(" +
				"Ljava/util/function/Supplier;" +
				"Lcom/mojang/blaze3d/textures/GpuTextureView;" +
				"Ljava/util/Optional;" +
				"Lcom/mojang/blaze3d/textures/GpuTextureView;" +
				"Ljava/util/OptionalDouble;" +
				")Lcom/mojang/blaze3d/systems/RenderPass;",
			passRedirect.at.target,
		)

		// The pipeline-boundary handler's @Redirect matches the mapped RenderPass descriptor.
		val pipelineHandler = mixinClass.getDeclaredMethod(
			"mcDlssWeatherSetPipeline",
			RenderPass::class.java,
			RenderPipeline::class.java,
		)
		val pipelineRedirect = requireNotNull(pipelineHandler.getAnnotation(Redirect::class.java))
		assertTrue(pipelineRedirect.method.contentEquals(arrayOf("render")))
		assertEquals("INVOKE", pipelineRedirect.at.value)
		assertEquals(
			"Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V",
			pipelineRedirect.at.target,
		)
	}

	@Test
	fun `weather twin is registered and reachable from the mixin through the variant surface`() {
		val variant = source("src/main/kotlin/me/snowmii/dlss/mrt/VelocityPipelineVariant.kt")
		assertTrue(variant.contains("fun weatherVelocityTwin(plainTwin: RenderPipeline)"))
		assertTrue(variant.contains("weatherVelocityTwins.computeIfAbsent"))
		assertTrue(variant.contains("withFragmentShader(WeatherVelocityRender.FRAGMENT_SHADER)"))
		assertTrue(variant.contains("withBindGroupLayout(WeatherVelocityRender.LAYOUT)"))

		// The registered JSON entry is the compile-time seam: a misspelled class name would
		// fail the mixin application in the client.
		val registered = JsonParser.parseString(source("src/main/resources/mc-dlss.mixins.json"))
			.asJsonObject
			.getAsJsonArray("client")
			.map { it.asString }
		assertTrue("WeatherEffectRendererMotionMixin" in registered)
	}

	private fun source(path: String) = repository.resolve(path).readText()

	private fun weatherShader(): String = repository
		.resolve("src/main/resources/assets/mc-dlss/shaders/core/velocity_weather.fsh")
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
				val filenameBuffer = MemoryUtil.memUTF8("velocity_weather.fsh")
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

	private fun assertVelocityTarget(target: ColorTargetState) {
		assertTrue(target.blendFunction().isEmpty())
		assertEquals(GpuFormat.RG16_FLOAT, target.format())
		assertEquals(ColorTargetState.WRITE_ALL, target.writeMask())
	}

	private val output = DlssDimensions(2560, 1440)
	private val render = DlssDimensions(1707, 960)
	private val mainTarget = FakeTarget(output.width, output.height)

	/** Sample points spread across the frustum, from near the eye to the far plane. */
	private val probes = listOf(
		Vector4f(0f, 0f, 0.95f, 1f),
		Vector4f(0.4f, 0.3f, 0.6f, 1f),
		Vector4f(-0.5f, 0.2f, 0.25f, 1f),
		Vector4f(0.1f, -0.4f, 0.05f, 1f),
	)

	private fun phase(runtime: RenderRuntime) = WorldPhase(
		runtime = runtime,
		present = { _, _ -> },
		onWorldTargetChanged = {},
	)

	private fun velocityRuntime(): RenderRuntime {
		val session = DlssSession(
			DlssStartupConfig(
				enabled = true,
				qualityMode = SRMode.QUALITY,
				outputDimensions = output,
				sdkPath = null,
				nativeLibraryPath = null,
				dataPath = null,
				warnings = emptyList(),
			),
		).also { check(it.markReadyAfterNativeStartup()) }
		return RenderRuntime(
			session = session,
			sceneTarget = SceneTarget(
				allocate = { width, height -> FakeTarget(width, height) },
				release = { (it as FakeTarget).releases++ },
				allocateVelocity = { width, height -> FakeTarget(width, height, GpuFormat.RG16_FLOAT, withView = true) },
			),
			startup = { render },
		)
	}

	private class FakeTarget(
		width: Int,
		height: Int,
		format: GpuFormat = GpuFormat.RGBA8_UNORM,
		withView: Boolean = false,
	) : RenderTarget("fake", true, format) {
		var releases = 0
		private val texture = FakeTexture(format, width, height)

		init {
			this.width = width
			this.height = height
			if (withView) {
				colorTextureView = FakeView(texture)
			}
		}

		override fun createBuffers(width: Int, height: Int) {
			this.width = width
			this.height = height
		}

		override fun destroyBuffers() {
			releases++
		}
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

		/** The shared VelocityConfig payload's sentinel, mirrored so the JVM classification asserts the same value. */
		const val INVALID_VELOCITY = 10000f
	}
}
