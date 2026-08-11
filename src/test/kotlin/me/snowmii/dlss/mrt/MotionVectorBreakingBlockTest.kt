package me.snowmii.dlss.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.buffers.GpuFence
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.shaders.ShaderSource
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.CommandEncoderBackend
import com.mojang.blaze3d.systems.DeviceFeatures
import com.mojang.blaze3d.systems.DeviceInfo
import com.mojang.blaze3d.systems.DeviceLimits
import com.mojang.blaze3d.systems.DeviceType
import com.mojang.blaze3d.systems.GpuDevice
import com.mojang.blaze3d.systems.GpuDeviceBackend
import com.mojang.blaze3d.systems.GpuQueryPool
import com.mojang.blaze3d.systems.GpuSurfaceBackend
import com.mojang.blaze3d.systems.HintsAndWorkarounds
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderPassBackend
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.systems.ScissorState
import com.mojang.blaze3d.systems.TransientMemory
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.nio.file.Path
import java.util.Optional
import java.util.OptionalDouble
import java.util.function.Supplier
import kotlin.io.path.readText
import kotlin.math.abs
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.SceneTarget
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import net.minecraft.SharedConstants
import net.minecraft.WorldVersion
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.PreparedRenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import net.minecraft.server.packs.metadata.pack.PackFormat
import net.minecraft.world.level.storage.DataVersion
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.joml.Vector4fc
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc
import org.lwjgl.util.spvc.Spvc
import org.lwjgl.util.spvc.SpvcReflectedResource
import com.google.gson.JsonParser

/**
 * Breaking-block crumbling vertical proof for M-6's velocity writer.
 *
 * `LevelRenderer.submitBlockDestroyAnimation` submits each breaking block's model parts into
 * the `breakingOverlay` phase with the `CRUMBLING` render type - `ModelBakery.DESTROY_TYPES`,
 * ten static stages of the mapped `pipeline/crumbling` pipeline (core/rendertype_crumbling
 * shaders, BLOCK vertex format, the DST_COLOR/SRC_COLOR multiply blend) - and
 * `RenderTypeFeatureRenderer.executeGroup` draws every crumbling overlay through the same
 * `PreparedRenderType.drawFromBuffer` seam the entity and moving-block writers use. This slice
 * recognizes those CRUMBLING draws at the prepared-draw dispatch, replaces only the owned
 * main-target crumbling draws with a cached two-target twin whose fragment shader reproduces
 * the vanilla rendertype_crumbling color output byte-identically and writes jitter-stripped NDC
 * camera motion into the RG16_FLOAT velocity attachment at color index 1 - the terrain writer's
 * existing VelocityConfig payload, because the crumbling overlay carries no block identity or
 * history of its own - with the exact reset/unknown-history sentinel. Vanilla, CAMERA_ONLY,
 * non-main, closed-phase, and failure routes keep their exact source draws without throwing.
 *
 * The test JVM does not apply Fabric mixins or own a live Blaze3D device, so this suite makes
 * no live transformed/GPU draw claim against a real device. Descriptors are proven against the
 * mapped 26.2 classes, the control seams are driven at the same seams the mixins use, and
 * passthrough is proven by the control seams answering false. The eligible production
 * [BreakingBlockVelocityRender.draw] itself is executed end to end on a recording fake command
 * backend (the writer's own seams): the attachment order, the writer twin, the payload write,
 * and every uniform/geometry bind run for real, with the dispatch result proving that a
 * successful replacement cancels the source draw while any injected failure - pass creation,
 * pipeline, uniform, bind, or draw submission - passes through so the exact vanilla draw
 * replays. The crumbling shader compiles through the same LWJGL
 * Shaderc + spirv-cross path `GlslCompiler` and `IntermediaryShaderModule` use - it inlines the
 * two vanilla includes it needs, so it is self-contained - and the reflected output order is
 * pinned to fragColor-then-velocityColor, the order Minecraft's location rewrite turns into
 * color attachments 0 and 1.
 */
class MotionVectorBreakingBlockTest {
	private val repository = Path.of("").toAbsolutePath()

	companion object {
		/**
		 * The headless test JVM needs the vanilla registry bootstrap before touching
		 * registry-backed render types, with a synthetic world version since no game entrypoint
		 * runs in tests. Idempotent.
		 */
		@JvmStatic
		@org.junit.jupiter.api.BeforeAll
		fun bootstrapVanillaRegistries() {
			SharedConstants.setVersion(
				WorldVersion.Simple(
					"26.2",
					"26.2",
					DataVersion(9999, "main"),
					1,
					PackFormat(1, 1),
					PackFormat(1, 1),
					java.util.Date(),
					true,
				),
			)
			Bootstrap.bootStrap()
		}

		/** SPIR-V DecorationLocation, the decoration `createFromSpirv` rewrites and this suite reads back. */
		const val LOCATION_DECORATION = 30

		/** The shared payload's sentinel, mirrored so the JVM classification asserts the same value. */
		const val INVALID_VELOCITY = 10000f
	}

	@Test
	fun `crumbling twin preserves every source descriptor and adds the velocity target`() {
		val source = RenderPipelines.CRUMBLING
		val plain = velocityTwin(source)
		val twin = crumblingVelocityTwin(plain)

		// The writer twin preserves every source descriptor field through the plain twin, with
		// exactly the writer's velocity shader swapped in for the source fragment shader.
		assertSame(source.vertexShader, twin.vertexShader)
		assertEquals(BreakingBlockVelocityRender.FRAGMENT_SHADER, twin.fragmentShader)
		assertEquals(source.shaderDefines, twin.shaderDefines)
		assertSame(source.depthStencilState, twin.depthStencilState)
		assertSame(source.polygonMode, twin.polygonMode)
		assertEquals(source.isCull, twin.isCull)
		assertSame(source.primitiveTopology, twin.primitiveTopology)
		assertSame(source.getVertexFormatBinding(0), twin.getVertexFormatBinding(0))
		assertSame(DefaultVertexFormat.BLOCK, twin.getVertexFormatBinding(0))

		// The source layouts (MATRICES_PROJECTION, FOG, SAMPLER0) stay, exactly one
		// VelocityConfig layout is added.
		assertEquals(source.bindGroupLayouts.size + 1, twin.bindGroupLayouts.size)
		for (index in source.bindGroupLayouts.indices) {
			assertSame(source.bindGroupLayouts[index], twin.bindGroupLayouts[index])
		}
		assertSame(BreakingBlockVelocityRender.LAYOUT, twin.bindGroupLayouts.last())
		assertSame(TerrainVelocityUniforms.LAYOUT, BreakingBlockVelocityRender.LAYOUT)

		// Target zero is the source crumbling overlay target - the DST_COLOR/SRC_COLOR multiply
		// blend intact - and target one is exactly the unblended RG16_FLOAT velocity payload.
		val sourceTargets = source.colorTargetStates
		val twinTargets = twin.colorTargetStates
		assertEquals(2, twinTargets.size)
		assertSame(sourceTargets[0], twinTargets[0])
		assertVelocityTarget(twinTargets[1]!!)
	}

	@Test
	fun `crumbling twin is cached per source and distinct from every other writer twin`() {
		val source = RenderPipelines.CRUMBLING
		val plain = velocityTwin(source)

		assertEquals(Identifier.fromNamespaceAndPath("mc-dlss", "velocity/pipeline/crumbling"), plain.location)
		assertSame(plain, velocityTwin(source), "the plain twin is cached per source pipeline")

		val twin = crumblingVelocityTwin(plain)
		assertSame(twin, crumblingVelocityTwin(plain), "the writer twin is cached per plain twin")
		assertEquals(Identifier.fromNamespaceAndPath("mc-dlss", "velocity/crumbling/crumbling"), twin.location)

		// The crumbling twin lives at its own location: no collision with the plain twin's
		// velocity/pipeline path or the terrain/entity/weather/particle/moving-block writer twins.
		assertNotEquals(plain.location, twin.location)
		assertNotEquals(terrainVelocityTwin(plain).location, twin.location)
		assertNotEquals(entityVelocityTwin(plain).location, twin.location)
		assertNotEquals(weatherVelocityTwin(plain).location, twin.location)
		assertNotEquals(particleVelocityTwin(plain).location, twin.location)
		assertNotEquals(movingBlockVelocityTwin(plain).location, twin.location)
	}

	@Test
	fun `only the owned crumbling pipeline is eligible for the breaking block writer`() {
		assertTrue(BreakingBlockVelocityRender.isSupportedPipeline(RenderPipelines.CRUMBLING))
		assertFalse(BreakingBlockVelocityRender.isSupportedPipeline(RenderPipelines.SOLID_BLOCK))
		assertFalse(BreakingBlockVelocityRender.isSupportedPipeline(RenderPipelines.SOLID_TERRAIN))
		assertFalse(BreakingBlockVelocityRender.isSupportedPipeline(RenderPipelines.ENTITY_SOLID))
		assertFalse(BreakingBlockVelocityRender.isSupportedPipeline(RenderPipelines.ITEM_CUTOUT))

		// The mapped crumbling render type binds exactly the crumbling pipeline on the main
		// target: `LevelRenderer.submitBlockDestroyAnimation` submits the breaking overlay with
		// ModelBakery.DESTROY_TYPES stages of this render type.
		val crumbling = RenderTypes.crumbling(Identifier.fromNamespaceAndPath("minecraft", "block/dirt"))
		assertSame(RenderPipelines.CRUMBLING, crumbling.pipeline())
		assertSame(OutputTarget.MAIN_TARGET, crumbling.outputTarget())

		val writer = source("src/main/kotlin/me/snowmii/dlss/mrt/BreakingBlockVelocityRender.kt")
		assertTrue(writer.contains("outputTarget() === OutputTarget.MAIN_TARGET"), "the render-type gate must keep non-main targets vanilla")
	}

	@Test
	fun `crumbling pass attachments agree with the twin on both routes`() {
		val scene = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM))
		val velocity = FakeView(FakeTexture(GpuFormat.RG16_FLOAT))

		// The vanilla route: the one-attachment draw binds the source crumbling pipeline.
		val oneTarget = com.mojang.blaze3d.systems.RenderPassDescriptor.create({ "Crumbling" })
			.withColorAttachment(scene)
			.withDepthAttachment(FakeView(FakeTexture(GpuFormat.D32_FLOAT)), OptionalDouble.empty())
		assertEquals(1, oneTarget.colorAttachments().size)
		assertEquals(RenderPipelines.CRUMBLING.colorTargetStates.size, oneTarget.colorAttachments().size)

		// The VELOCITY_MRT route: the two-attachment pass must agree with the two-target twin -
		// exactly the count/format check RenderPass.setPipeline performs on first bind.
		val twin = crumblingVelocityTwin(velocityTwin(RenderPipelines.CRUMBLING))
		val twoTarget = com.mojang.blaze3d.systems.RenderPassDescriptor.create({ "Crumbling velocity" })
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
	fun `crumbling shader preserves the overlay color output and writes velocity after the final color`() {
		val shader = crumblingShader()

		// The vanilla core/rendertype_crumbling fragment body the crumbling pipeline binds,
		// verbatim - including the discard that sits between the vertex-color multiply and the
		// ColorModulator multiply, so the overlay's alpha cut and darken blend stay byte-identical.
		assertTrue(shader.contains("texture(Sampler0, texCoord0) * vertexColor"))
		assertTrue(shader.contains("color.a < 0.1"))
		assertTrue(shader.contains("discard"))
		assertTrue(shader.contains("color = color * ColorModulator"))
		assertTrue(shader.contains("fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor)"))
		val discard = shader.indexOf("discard")
		val modulator = shader.indexOf("color = color * ColorModulator")
		assertTrue(discard >= 0 && modulator >= 0 && discard < modulator, "the crumbling discard runs before the ColorModulator multiply")

		// The inlined vanilla includes it needs: fog and dynamic transforms (ColorModulator).
		assertTrue(shader.contains("layout(std140) uniform Fog {"))
		assertTrue(shader.contains("layout(std140) uniform DynamicTransforms {"))
		assertTrue(shader.contains("vec4 ColorModulator;"))
		assertTrue(shader.contains("uniform sampler2D Sampler0;"))

		// The payload: the existing terrain VelocityConfig block and the velocity output, with
		// the exact sentinel every scene writer uses.
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
	 * The compiled seam, exercising the true mechanism: the crumbling shader is self-contained
	 * (it inlines the two vanilla includes instead of #moj_import, which needs Minecraft's
	 * resource preprocessor), so it can be compiled through the same LWJGL Shaderc + spirv-cross
	 * path `GlslCompiler.createIntermediary` and `IntermediaryShaderModule.createFromSpirv` use.
	 * The stage-output reflection list must come back fragColor-first (that list's index is what
	 * the location rewrite writes), and applying the rewrite must leave fragColor on Location 0
	 * (the scene color) and velocityColor on Location 1 (the velocity attachment).
	 */
	@Test
	fun `crumbling shader reflects outputs in fragColor-then-velocityColor order through Minecraft's compile path`() {
		val spirv = compileFragmentShader(minecraftFragmentSource(crumblingShader()))
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
				"after the createFromSpirv rewrite fragColor must sit on attachment 0 (scene color) and " +
					"velocityColor on attachment 1 (velocity)",
			)
		} finally {
			MemoryUtil.memFree(spirv)
		}
	}

	/**
	 * The crumbling shader writes the terrain camera reprojection - the jitter-stripped
	 * current-to-previous clip mapping the DLSS evaluation receives - and the shared
	 * classification collapses reset frames and missing predecessors to the one representable
	 * sentinel instead of the identity-derived zero.
	 */
	@Test
	fun `crumbling shader writes the camera reprojection with the exact sentinel on reset and unknown history`() {
		val shader = crumblingShader()
		assertTrue(shader.contains("vec4 clip = vec4(ndc, gl_FragCoord.z, 1.0);"))
		assertTrue(shader.contains("vec4 previous = Reprojection * clip;"))
		assertTrue(shader.contains("previous.xy / previous.w - ndc"))
		assertTrue(shader.contains("VelocityParams.x > 0.5"), "the reset flag drives the per-pixel classification")
		assertTrue(shader.contains("vec4(INVALID_VELOCITY, INVALID_VELOCITY, 0.0, 0.0)"), "every invalid path writes the one sentinel")

		// The classification behavior: a reset frame (identity reprojection) must force the
		// sentinel at every probe - the identity would otherwise read as a still overlay - and a
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
	fun `eligible open velocity-mrt phase admits the breaking block control seam and ineligible routes fall through`() {
		val runtime = dlssRuntime()
		val phase = phase(runtime)
		val info = emptyExecuteInfo()
		try {
			renderFrame(phase)
			phase.prepare(true, mainTarget, camera())
			phase.begin(true, mainTarget)

			// A crumbling draw on the main target is eligible while the velocity view is offered.
			val crumbling = PreparedRenderType(RenderPipelines.CRUMBLING, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
			assertTrue(BreakingBlockVelocityRender.canDraw(crumbling, info, phase))

			// The non-main route: a crumbling draw aimed at another target keeps vanilla.
			val foreignTarget = PreparedRenderType(RenderPipelines.CRUMBLING, OutputTarget.ITEM_ENTITY_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
			assertFalse(BreakingBlockVelocityRender.canDraw(foreignTarget, info, phase))

			// Unsupported pipelines fall through to their exact source draw.
			val block = PreparedRenderType(RenderPipelines.SOLID_BLOCK, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
			assertFalse(BreakingBlockVelocityRender.canDraw(block, info, phase))
			val terrain = PreparedRenderType(RenderPipelines.SOLID_TERRAIN, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
			assertFalse(BreakingBlockVelocityRender.canDraw(terrain, info, phase))

			// The draw replacement falls through without a live ClientRuntime phase: the phase
			// gate answers false before anything can touch a device, so the draw never throws.
			assertFalse(
				BreakingBlockVelocityRender.draw(crumbling, info),
				"headless: the draw must answer false at the phase gate, never throw",
			)
		} finally {
			if (phase.isOpen) phase.end()
		}
	}

	@Test
	fun `vanilla camera-only and non-open phases keep the breaking block route unchanged`() {
		// Camera-only: the first foreign pipeline latches the fallback route, so the open phase
		// offers no velocity view and the writer answers false - the exact source draw survives.
		val cameraOnly = dlssRuntime()
		cameraOnly.observeWorldPipeline(
			MotionVectorPipeline(
				"example:pipeline/waving_terrain",
				listOf(MotionVectorShader("example:core/waving_terrain", "example")),
			),
		)
		assertEquals(MotionVectorRoute.CAMERA_ONLY, cameraOnly.motionVectorRoute)
		val cameraOnlyPhase = phase(cameraOnly)
		val info = emptyExecuteInfo()
		assertFalse(
			BreakingBlockVelocityRender.canDraw(
				PreparedRenderType(RenderPipelines.CRUMBLING, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList()),
				info,
				cameraOnlyPhase,
			),
			"a camera-only phase offers no velocity view",
		)
		assertFalse(BreakingBlockVelocityRender.draw(PreparedRenderType(RenderPipelines.CRUMBLING, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList()), info))

		// Vanilla: a session without DLSS keeps the crumbling draw on its exact source route.
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
		assertFalse(
			BreakingBlockVelocityRender.canDraw(
				PreparedRenderType(RenderPipelines.CRUMBLING, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList()),
				info,
				vanillaPhase,
			),
		)

		// A closed phase offers no velocity view either.
		assertFalse(
			BreakingBlockVelocityRender.canDraw(
				PreparedRenderType(RenderPipelines.CRUMBLING, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList()),
				info,
				null,
			),
			"a closed phase keeps the crumbling draw vanilla",
		)
	}

	@Test
	fun `crumbling writer is dispatched from the prepared draw seam and registered through the variant surface`() {
		val variant = source("src/main/kotlin/me/snowmii/dlss/mrt/VelocityPipelineVariant.kt")
		assertTrue(variant.contains("fun crumblingVelocityTwin(plainTwin: RenderPipeline)"))
		assertTrue(variant.contains("crumblingVelocityTwins.computeIfAbsent"))
		assertTrue(variant.contains("withFragmentShader(BreakingBlockVelocityRender.FRAGMENT_SHADER)"))
		assertTrue(variant.contains("withBindGroupLayout(BreakingBlockVelocityRender.LAYOUT)"))

		val writer = source("src/main/kotlin/me/snowmii/dlss/mrt/BreakingBlockVelocityRender.kt")
		assertTrue(writer.contains("TerrainVelocityUniforms.writeFrame"), "the writer fills the existing terrain VelocityConfig payload")
		assertTrue(writer.contains("phase.activeMotion"), "the writer publishes the frame's camera reprojection")
		assertTrue(writer.contains("terrainVelocityView"), "the scene velocity view gates the writer")
		assertTrue(writer.contains("crumblingVelocityTwin(velocityTwin(prepared.pipeline()))"))

		// The prepared-draw dispatch recognizes CRUMBLING: the existing drawFromBuffer HEAD
		// inject consults the breaking-block writer alongside the moving-block and entity
		// writers, and the mixin config already registers that mixin class.
		val prepared = source("src/main/java/me/snowmii/dlss/mixin/PreparedRenderTypeMotionMixin.java")
		assertTrue(prepared.contains("BreakingBlockVelocityRender.draw"), "the prepared draw dispatch consults the crumbling writer")
		val mixins = JsonParser.parseString(source("src/main/resources/mc-dlss.mixins.json")).asJsonObject.getAsJsonArray("client").map { it.asString }
		assertTrue("PreparedRenderTypeMotionMixin" in mixins, "the crumbling dispatch rides the already-registered prepared draw mixin")
	}

	@Test
	fun `crumbling draw preflights before ownership and passes failures through to the source draw`() {
		val writer = source("src/main/kotlin/me/snowmii/dlss/mrt/BreakingBlockVelocityRender.kt")

		// The draw failure decision: every gate, resource, twin, and descriptor input is
		// preflighted before the writer takes ownership (the device encoder), and a failure
		// anywhere - preflight or owned - answers false, so `PreparedRenderType.drawFromBuffer`'s
		// exact vanilla one-target implementation stays in control and the crumbling overlay is
		// drawn instead of silently dropped. There is no committed-draw disposition left that
		// cancels the source draw on a failed replacement.
		val draw = writer.substring(writer.indexOf("fun draw("))
		assertTrue(draw.contains("createCommandEncoder()"), "the ownership boundary is the device encoder")
		assertTrue(
			draw.indexOf("createCommandEncoder()") > draw.indexOf("crumblingVelocityTwin(velocityTwin(prepared.pipeline()))"),
			"the twin is preflighted before ownership",
		)
		assertTrue(
			draw.indexOf("createCommandEncoder()") > draw.indexOf("runCatching { buffer() }"),
			"the payload buffer is preflighted before ownership",
		)
		assertTrue(draw.contains("catch"), "a failure after ownership is caught")
		val catchTail = draw.substring(draw.indexOf("catch"))
		assertTrue(
			catchTail.contains("false"),
			"every caught failure answers false so the dispatch never cancels a failed replacement",
		)
		assertFalse(draw.contains("committedDrawFailure"), "the committed-draw disposition is gone: a failed draw is not consumed")

		// The writer's payload shape is the existing terrain VelocityConfig block.
		assertEquals(TerrainVelocityUniforms.UNIFORM_NAME, BreakingBlockVelocityRender.UNIFORM_NAME)
		assertEquals("core/velocity_crumbling", BreakingBlockVelocityRender.SHADER_PATH)
		assertEquals(Identifier.fromNamespaceAndPath("mc-dlss", "core/velocity_crumbling"), BreakingBlockVelocityRender.FRAGMENT_SHADER)
		assertSame(TerrainVelocityUniforms.LAYOUT, BreakingBlockVelocityRender.LAYOUT)
	}

	/**
	 * Executes the eligible production crumbling draw end to end on a recording fake command
	 * backend: the writer's own gates, twin cache lookup, payload write, pass descriptor, and
	 * every bind run for real, with only the device (encoder/buffer allocation) and the
	 * output-target resolution faked through the writer's seams. A true result means the
	 * prepared-draw dispatch cancels the source draw only after the two-attachment replacement
	 * fully recorded, and the recorded sequence is the contract: scene color at attachment 0,
	 * the scene-sized velocity view at attachment 1, the cached crumbling writer twin accepted
	 * against those attachments by the real `RenderPass.setPipeline` validation, the terrain
	 * VelocityConfig payload write plus DynamicTransforms and payload uniform binds, the source
	 * geometry binds, and the draw submission.
	 */
	@Test
	fun `eligible crumbling draw executes on a fake command backend and records the full replacement`() {
		val runtime = dlssRuntimeWithViews()
		val phase = phase(runtime)
		val crumbling = PreparedRenderType(RenderPipelines.CRUMBLING, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
		val info = emptyExecuteInfo()
		try {
			renderFrame(phase)
			phase.prepare(true, mainTarget, camera())
			phase.begin(true, mainTarget)
			val scene = checkNotNull(phase.worldTargetOverride)
			val velocityView = checkNotNull(phase.terrainVelocityView)

			val backend = FakeCommandBackend()
			BreakingBlockVelocityRender.activePhaseOverride = phase
			BreakingBlockVelocityRender.deviceProvider = { backend.device }
			BreakingBlockVelocityRender.outputTargetResolver = { scene }

			// The production draw calls RenderSystem.bindDefaultUniforms, which throws off the
			// render thread; record this test thread as the render thread (once per JVM, nothing
			// else does) so the draw runs the way it does in the client.
			runCatching { RenderSystem.initRenderThread() }.getOrNull()

			assertTrue(
				BreakingBlockVelocityRender.draw(crumbling, info),
				"a fully recorded two-attachment replacement answers true so the dispatch cancels the source draw",
			)

			// Attachment order: the source scene color at index 0, the scene-sized RG16_FLOAT
			// velocity view at index 1, rendered over the scene's size, with no depth attachment
			// (the fake main target owns no depth view).
			val descriptor = checkNotNull(backend.renderPassDescriptor)
			val attachments = descriptor.colorAttachments()
			assertEquals(2, attachments.size)
			assertSame(scene.colorTextureView, attachments[0]!!.textureView())
			assertSame(velocityView, attachments[1]!!.textureView())
			assertTrue(attachments[1]!!.clearValue().isEmpty(), "the velocity attachment is never cleared")
			assertEquals(scene.width, checkNotNull(descriptor.renderArea).width(), "the pass renders over the scene's size")
			assertEquals(scene.height, checkNotNull(descriptor.renderArea).height())
			assertNull(descriptor.depthAttachment())

			// The writer twin: the cached crumbling twin of the source pipeline, and the real
			// RenderPass.setPipeline validation accepted its two targets against the attachments.
			assertSame(crumblingVelocityTwin(velocityTwin(RenderPipelines.CRUMBLING)), backend.pipeline)

			// The payload: the frame's VelocityConfig block was written onto the writer's cached
			// payload buffer through the fake encoder, then bound under the terrain writer's
			// uniform name after the source DynamicTransforms bind.
			assertEquals(TerrainVelocityUniforms.UBO_SIZE, backend.payloadBytes, "the payload write carries the full VelocityConfig block")
			assertEquals(
				listOf("DynamicTransforms", BreakingBlockVelocityRender.UNIFORM_NAME),
				backend.uniforms.map { it.first },
				"the pass binds the source dynamic transforms then the velocity payload",
			)
			assertSame(crumbling.dynamicTransforms().buffer(), backend.uniforms[0].second.buffer())
			assertSame(backend.payloadBuffer, backend.uniforms[1].second.buffer())

			// The geometry: the execute-info vertex buffer on slot 0, the execute-info index
			// buffer with the source index type, and exactly the source draw submission.
			assertEquals(0, backend.vertexBuffers.single().first)
			assertSame(info.vertexBuffer(), backend.vertexBuffers.single().second!!.buffer())
			assertSame(info.indexBuffer(), backend.indexBuffer)
			assertEquals(IndexType.INT, backend.indexType)
			assertArrayEquals(intArrayOf(3, 1, 0, 0, 0), backend.draws.single(), "indexCount, instances, firstIndex, baseVertex, firstInstance")

			// The owned pass closed cleanly through the use block, so the writer left no dangling
			// pass state behind a successful replacement.
			assertTrue(backend.passClosed)
		} finally {
			resetSeams()
			if (phase.isOpen) phase.end()
		}
	}

	/**
	 * Injects a failure at every device call the eligible draw makes and asserts the draw
	 * answers false - the dispatch does not cancel, and the exact vanilla one-target draw
	 * replays - without ever throwing out of the writer. The pre-fix disposition answered true
	 * after any caught failure, cancelling the source draw and silently dropping the crumbling
	 * overlay; these assertions are the regression net that catches that behavior.
	 */
	@Test
	fun `injected failure anywhere in the eligible draw passes through to the exact source draw`() {
		val runtime = dlssRuntimeWithViews()
		val phase = phase(runtime)
		val crumbling = PreparedRenderType(RenderPipelines.CRUMBLING, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
		val info = emptyExecuteInfo()
		try {
			renderFrame(phase)
			phase.prepare(true, mainTarget, camera())
			phase.begin(true, mainTarget)
			val scene = checkNotNull(phase.worldTargetOverride)
			BreakingBlockVelocityRender.activePhaseOverride = phase
			BreakingBlockVelocityRender.outputTargetResolver = { scene }

			// Every device call the owned region makes, injected to throw: encoder allocation,
			// payload buffer allocation, the payload write, pass creation, pipeline bind, uniform
			// bind, vertex/index bind, and the draw submission. Each must pass through (false),
			// never cancel the source draw, and never throw out of the writer.
			for (failurePoint in listOf(
				"createBuffer",
				"createCommandEncoder",
				"writeToBuffer",
				"createRenderPass",
				"setPipeline",
				"setUniform",
				"setVertexBuffer",
				"setIndexBuffer",
				"drawIndexed",
			)) {
				// Drop the writer's cached payload allocation so the createBuffer injection is
				// actually consulted on the first iteration; the cache is a singleton, so a prior
				// test's successful allocation would otherwise mask it.
				BreakingBlockVelocityRender.resetPayloadBuffer()
				val backend = FakeCommandBackend().also { it.failAt = failurePoint }
				BreakingBlockVelocityRender.deviceProvider = { backend.device }
				assertFalse(
					BreakingBlockVelocityRender.draw(crumbling, info),
					"$failurePoint: a failed replacement must answer false so the source draw replays",
				)
			}

			// An unexpected preflight throw also degrades to passthrough: the eligibility reads
			// must never throw out of the writer, and the source draw stays replayable.
			BreakingBlockVelocityRender.outputTargetResolver = { throw IllegalStateException("injected target resolution failure") }
			assertFalse(
				BreakingBlockVelocityRender.draw(crumbling, info),
				"a preflight failure must answer false, never throw",
			)
		} finally {
			resetSeams()
			if (phase.isOpen) phase.end()
		}
	}

	private fun resetSeams() {
		BreakingBlockVelocityRender.activePhaseOverride = null
		BreakingBlockVelocityRender.deviceProvider = { RenderSystem.getDevice() }
		BreakingBlockVelocityRender.outputTargetResolver = { it.getRenderTarget() }
	}

	private fun source(path: String) = repository.resolve(path).readText()

	private fun crumblingShader(): String = repository
		.resolve("src/main/resources/assets/mc-dlss/shaders/core/velocity_crumbling.fsh")
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
				val filenameBuffer = MemoryUtil.memUTF8("velocity_crumbling.fsh")
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

	private fun renderFrame(phase: WorldPhase) {
		phase.prepare(true, mainTarget, camera())
		phase.begin(true, mainTarget)
		phase.end()
	}

	private fun phase(runtime: RenderRuntime, evaluate: Boolean = true) = WorldPhase(
		runtime = runtime,
		present = { _, _ -> },
		onWorldTargetChanged = {},
		evaluateFrame = { _, _, _, _, _, _ -> evaluate },
	)

	private fun dlssRuntime(): RenderRuntime {
		val session = DlssSession(config()).also { check(it.markReadyAfterNativeStartup()) }
		return RenderRuntime(
			session = session,
			sceneTarget = SceneTarget(
				allocate = { width, height -> FakeTarget(width, height) },
				release = { (it as FakeTarget).releases++ },
				allocateVelocity = { width, height -> FakeTarget(width, height, GpuFormat.RG16_FLOAT, withView = true) },
			),
			startup = { DlssDimensions(1280, 720) },
		)
	}

	/**
	 * The same runtime as [dlssRuntime], but the scene target carries a color view too, so the
	 * eligible production draw can resolve its attachments from `phase.worldTargetOverride`.
	 */
	private fun dlssRuntimeWithViews(): RenderRuntime {
		val session = DlssSession(config()).also { check(it.markReadyAfterNativeStartup()) }
		return RenderRuntime(
			session = session,
			sceneTarget = SceneTarget(
				allocate = { width, height -> FakeTarget(width, height, GpuFormat.RGBA8_UNORM, withView = true) },
				release = { (it as FakeTarget).releases++ },
				allocateVelocity = { width, height -> FakeTarget(width, height, GpuFormat.RG16_FLOAT, withView = true) },
			),
			startup = { DlssDimensions(1280, 720) },
		)
	}

	private fun config() = DlssStartupConfig(
		enabled = true,
		qualityMode = SRMode.QUALITY,
		outputDimensions = DlssDimensions(2560, 1440),
		sdkPath = null,
		nativeLibraryPath = null,
		dataPath = null,
		warnings = emptyList(),
	)

	private fun camera() = DlssCameraSample(
		projection = Matrix4f().setPerspective(
			Math.toRadians(70.0).toFloat(),
			2560f / 1440f,
			1000f,
			0.05f,
			true,
		),
		viewRotation = Matrix4f(),
		cameraX = 0.0,
		cameraY = 64.0,
		cameraZ = 0.0,
	)

	private fun emptyExecuteInfo() = StagedVertexBuffer.ExecuteInfo(
		FakeBuffer(),
		FakeBuffer(),
		IndexType.INT,
		0,
		0,
		3,
	)

	private val output = DlssDimensions(2560, 1440)
	private val mainTarget = FakeTarget(2560, 1440)

	/** Sample points spread across the frustum, from near the eye to the far plane. */
	private val probes = listOf(
		Vector4f(0f, 0f, 0.95f, 1f),
		Vector4f(0.4f, 0.3f, 0.6f, 1f),
		Vector4f(-0.5f, 0.2f, 0.25f, 1f),
		Vector4f(0.1f, -0.4f, 0.05f, 1f),
	)

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
			if (withView) colorTextureView = FakeView(texture)
		}

		override fun createBuffers(width: Int, height: Int) {
			this.width = width
			this.height = height
		}

		override fun destroyBuffers() {
			releases++
		}
	}

	private class FakeBuffer : GpuBuffer(GpuBuffer.USAGE_VERTEX, 0) {
		override fun isClosed() = false
		override fun close() = Unit
		override fun map(offset: Long, length: Long, read: Boolean, write: Boolean): GpuBufferSlice.MappedView =
			throw UnsupportedOperationException("test buffer is never mapped")
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

	/**
	 * The recording fake command backend the eligible production draw executes on. The writer's
	 * three seams - the phase, the device, and the output-target resolution - point at this
	 * fixture; everything else in `BreakingBlockVelocityRender.draw` runs for real, including
	 * the descriptor build, the twin cache lookup, the real `RenderPass` constructor, and the
	 * real `RenderPass.setPipeline`/`setUniform`/`setVertexBuffer`/`drawIndexed` validation
	 * against the fake backends. [failAt] names one device call to make throw, for the injected
	 * passthrough evidence.
	 */
	private class FakeCommandBackend {
		var failAt: String? = null

		val payloadBuffer = FakeBuffer()
		var renderPassDescriptor: RenderPassDescriptor? = null
		var pipeline: RenderPipeline? = null
		val uniforms = mutableListOf<Pair<String, GpuBufferSlice>>()
		val vertexBuffers = mutableListOf<Pair<Int, GpuBufferSlice?>>()
		var indexBuffer: GpuBuffer? = null
		var indexType: IndexType? = null
		val draws = mutableListOf<IntArray>()
		var payloadBytes: Int = -1
		var passClosed = false

		private val deviceInfo = DeviceInfo(
			"fake",
			"fake",
			"fake",
			true,
			"fake",
			1f,
			DeviceLimits(16, 4, 65536, 1L shl 30, 1, 8),
			DeviceFeatures(true, false, false, false, false, true, false),
			emptySet(),
			HintsAndWorkarounds(false, false),
			DeviceType.OTHER,
		)
		private val deviceBackend = RecordingGpuDeviceBackend(deviceInfo)
		private val passBackend = RecordingPassBackend(this)

		val encoder: CommandEncoder = RecordingEncoder(this, deviceBackend, passBackend)
		val device: GpuDevice = RecordingDevice(this, encoder, deviceBackend)

		fun failIf(point: String) {
			if (failAt == point) {
				throw IllegalStateException("injected $point failure")
			}
		}
	}

	/** The fake device: hands out the recording encoder and the writer's cached payload buffer. */
	private class RecordingDevice(
		private val backend: FakeCommandBackend,
		private val encoder: CommandEncoder,
		deviceBackend: GpuDeviceBackend,
	) : GpuDevice(deviceBackend, {}) {
		override fun createCommandEncoder(): CommandEncoder {
			backend.failIf("createCommandEncoder")
			return encoder
		}

		override fun createBuffer(label: Supplier<String>?, usage: Int, size: Long): GpuBuffer {
			backend.failIf("createBuffer")
			return backend.payloadBuffer
		}
	}

	/**
	 * The recording encoder: records the pass descriptor (the attachment order evidence) and the
	 * payload write, then builds the real [RenderPass] over the recording backends so the
	 * writer's pass body runs against real validation.
	 */
	private class RecordingEncoder(
		private val backend: FakeCommandBackend,
		private val deviceBackend: GpuDeviceBackend,
		private val passBackend: RenderPassBackend,
	) : CommandEncoder(null, deviceBackend, RecordingCommandEncoderBackend()) {
		override fun createRenderPass(descriptor: RenderPassDescriptor): RenderPass {
			backend.failIf("createRenderPass")
			backend.renderPassDescriptor = descriptor
			return RenderPass(
				passBackend,
				deviceBackend,
				descriptor.colorAttachments(),
				{ backend.passClosed = true },
				descriptor.renderArea,
			)
		}

		override fun writeToBuffer(destination: GpuBufferSlice, data: ByteBuffer) {
			backend.failIf("writeToBuffer")
			backend.payloadBytes = data.remaining()
		}
	}

	/** Records the pass-body calls the writer makes; every failure point can be injected. */
	private class RecordingPassBackend(private val backend: FakeCommandBackend) : RenderPassBackend {
		override fun pushDebugGroup(label: Supplier<String>) = Unit

		override fun popDebugGroup() = Unit

		override fun setPipeline(pipeline: RenderPipeline) {
			backend.failIf("setPipeline")
			backend.pipeline = pipeline
		}

		override fun bindTexture(name: String, textureView: GpuTextureView?, sampler: GpuSampler?) {
			backend.failIf("bindTexture")
		}

		override fun setUniform(name: String, value: GpuBuffer) = setUniform(name, GpuBufferSlice(value, 0, value.size()))

		override fun setUniform(name: String, value: GpuBufferSlice) {
			backend.failIf("setUniform")
			backend.uniforms.add(name to value)
		}

		override fun enableScissor(x: Int, y: Int, width: Int, height: Int) {
			backend.failIf("enableScissor")
		}

		override fun disableScissor() = Unit

		override fun setVertexBuffer(slot: Int, vertexBuffer: GpuBufferSlice?) {
			backend.failIf("setVertexBuffer")
			backend.vertexBuffers.add(slot to vertexBuffer)
		}

		override fun setIndexBuffer(indexBuffer: GpuBuffer, indexType: IndexType) {
			backend.failIf("setIndexBuffer")
			backend.indexBuffer = indexBuffer
			backend.indexType = indexType
		}

		override fun drawIndexed(indexCount: Int, instanceCount: Int, firstIndex: Int, vertexOffset: Int, firstInstance: Int) {
			backend.failIf("drawIndexed")
			backend.draws.add(intArrayOf(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance))
		}

		override fun multiDrawIndexed(drawParameters: IntBuffer, instanceCount: Int, firstInstance: Int, drawCount: Int) = Unit

		override fun multiDrawIndexed(firstIndexOffsets: PointerBuffer, indexCounts: IntBuffer, vertexOffsets: IntBuffer, drawCount: Int) = Unit

		override fun drawIndexedIndirect(commands: GpuBufferSlice, drawCount: Int) = Unit

		override fun <T : Any> drawMultipleIndexed(
			draws: Collection<RenderPass.Draw<T>>,
			defaultIndexBuffer: GpuBuffer?,
			defaultIndexType: IndexType?,
			dynamicUniforms: Collection<String>,
			uniformArgument: T,
		) = Unit

		override fun draw(vertexCount: Int, instanceCount: Int, firstVertex: Int, firstInstance: Int) = Unit

		override fun multiDraw(drawParameters: IntBuffer, instanceCount: Int, firstInstance: Int, drawCount: Int) = Unit

		override fun multiDraw(firstVertices: IntBuffer, vertexCounts: IntBuffer, drawCount: Int) = Unit

		override fun drawIndirect(commands: GpuBufferSlice, drawCount: Int) = Unit

		override fun writeTimestamp(pool: GpuQueryPool, index: Int) = Unit
	}

	/** The no-op encoder backend the real CommandEncoder constructor requires; never driven. */
	private class RecordingCommandEncoderBackend : CommandEncoderBackend {
		override fun submit() = Unit
		override fun transientMemory(): TransientMemory = throw UnsupportedOperationException("test backend never allocates transient memory")
		override fun createRenderPass(descriptor: RenderPassDescriptor): RenderPassBackend = throw UnsupportedOperationException("test backend never creates passes")
		override fun submitRenderPass() = Unit
		override fun clearColorTexture(colorTexture: GpuTexture, clearColor: Vector4fc) = Unit
		override fun clearColorAndDepthTextures(colorTexture: GpuTexture, clearColor: Vector4fc, depthTexture: GpuTexture, clearDepth: Double) = Unit
		override fun clearColorAndDepthTextures(colorTexture: GpuTexture, clearColor: Vector4fc, depthTexture: GpuTexture, clearDepth: Double, regionX: Int, regionY: Int, regionWidth: Int, regionHeight: Int) = Unit
		override fun clearDepthTexture(depthTexture: GpuTexture, clearDepth: Double) = Unit
		override fun writeToBuffer(destination: GpuBufferSlice, data: ByteBuffer) = Unit
		override fun copyToBuffer(source: GpuBufferSlice, target: GpuBufferSlice) = Unit
		override fun writeToTexture(destination: GpuTexture, source: ByteBuffer, mipLevel: Int, depthOrLayer: Int, destX: Int, destY: Int, width: Int, height: Int) = Unit
		override fun copyBufferToTexture(source: GpuBufferSlice, sourceX: Int, sourceY: Int, sourceWidth: Int, sourceHeight: Int, destination: GpuTexture, destinationX: Int, destinationY: Int, copyWidth: Int, copyHeight: Int, mipLevel: Int, arrayLayer: Int) = Unit
		override fun copyTextureToBuffer(source: GpuTexture, destination: GpuBuffer, offset: Long, callback: Runnable, mipLevel: Int) = Unit
		override fun copyTextureToBuffer(source: GpuTexture, destination: GpuBuffer, offset: Long, callback: Runnable, mipLevel: Int, x: Int, y: Int, width: Int, height: Int) = Unit
		override fun copyTextureToTexture(source: GpuTexture, destination: GpuTexture, mipLevel: Int, destX: Int, destY: Int, sourceX: Int, sourceY: Int, width: Int, height: Int) = Unit
		override fun createFence(): GpuFence = throw UnsupportedOperationException("test backend never creates fences")
		override fun writeTimestamp(pool: GpuQueryPool, index: Int) = Unit
	}

	/** The device-info side of the fake device, so the real RenderPass constructor and validation can run. */
	private class RecordingGpuDeviceBackend(private val info: DeviceInfo) : GpuDeviceBackend {
		override fun createSurface(windowHandle: Long): GpuSurfaceBackend = throw UnsupportedOperationException("test backend never creates surfaces")

		override fun createCommandEncoder(): CommandEncoderBackend = throw UnsupportedOperationException("test backend never creates encoders")

		override fun createSampler(
			addressModeU: AddressMode,
			addressModeV: AddressMode,
			minFilter: FilterMode,
			magFilter: FilterMode,
			maxAnisotropy: Int,
			maxLod: OptionalDouble,
		): GpuSampler = throw UnsupportedOperationException("test backend never creates samplers")

		override fun createTexture(label: Supplier<String>?, usage: Int, format: GpuFormat, width: Int, height: Int, depthOrLayers: Int, mipLevels: Int): GpuTexture =
			throw UnsupportedOperationException("test backend never creates textures")

		override fun createTexture(label: String?, usage: Int, format: GpuFormat, width: Int, height: Int, depthOrLayers: Int, mipLevels: Int): GpuTexture =
			throw UnsupportedOperationException("test backend never creates textures")

		override fun createTextureView(texture: GpuTexture): GpuTextureView = throw UnsupportedOperationException("test backend never creates texture views")

		override fun createTextureView(texture: GpuTexture, baseMipLevel: Int, mipLevels: Int): GpuTextureView =
			throw UnsupportedOperationException("test backend never creates texture views")

		override fun createBuffer(label: Supplier<String>?, usage: Int, size: Long): GpuBuffer =
			throw UnsupportedOperationException("test backend never allocates buffers")

		override fun createBuffer(label: Supplier<String>?, usage: Int, data: ByteBuffer): GpuBuffer =
			throw UnsupportedOperationException("test backend never allocates buffers")

		override fun getLastDebugMessages(): List<String> = emptyList()

		override fun isDebuggingEnabled(): Boolean = false

		override fun precompilePipeline(pipeline: RenderPipeline, shaderSource: ShaderSource?): CompiledRenderPipeline =
			throw UnsupportedOperationException("test backend never compiles pipelines")

		override fun clearPipelineCache() = Unit

		override fun close() = Unit

		override fun createTimestampQueryPool(size: Int): GpuQueryPool = throw UnsupportedOperationException("test backend never creates query pools")

		override fun getTimestampNow(): Long = 0L

		override fun getDeviceInfo(): DeviceInfo = info
	}
}
