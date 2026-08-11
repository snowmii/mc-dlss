package me.snowmii.dlss.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.buffers.GpuFence
import com.mojang.blaze3d.pipeline.BlendFunction
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
import com.mojang.blaze3d.systems.TransientMemory
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
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
import me.snowmii.dlss.render.DlssJitterOffset
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.SceneTarget
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import net.minecraft.client.CloudStatus
import net.minecraft.client.renderer.CloudRenderer
import net.minecraft.client.renderer.MappableRingBuffer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.joml.Vector4fc
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
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
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.Redirect
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import com.google.gson.JsonParser

/**
 * Cloud vertical proof for M-6's velocity writer.
 *
 * `CloudRenderer.render` is the last bespoke world pass before the protected hand seam: it
 * creates one pass over the clouds target (or the main target without the transparency chain)
 * with `RenderPipelines.CLOUDS` or `RenderPipelines.FLAT_CLOUDS` and draws the CPU-baked cloud
 * cells through the `CloudFaces` texel buffer, `CloudInfo`/`DynamicTransforms` uniforms, and a
 * QUADS index draw. This slice redirects only that pass creation while an open VELOCITY_MRT
 * world phase offers the scene velocity view: the pass gets the scene-sized RG16_FLOAT velocity
 * attachment at color index 1, the pipeline-boundary seam swaps in a cached cloud twin that
 * preserves the source cloud descriptors (translucent blended target zero, no vertex format
 * bindings - the geometry comes from CloudFaces and gl_VertexID - quads, depth, and the flat
 * variant's cull-off state) plus the mc-dlss cloud velocity fragment shader and the
 * CloudVelocityConfig layout, and the writer fills that payload with this frame's cloud-offset
 * drift composed into the camera reprojection - the invalid sentinel on a mesh rebuild, a clock
 * discontinuity, a reset frame, or a frame without a predecessor. Vanilla and CAMERA_ONLY
 * routes keep the exact source pass: the control seam answers false and the redirect falls
 * through to the vanilla one-attachment creation, never throwing.
 *
 * The test JVM does not apply Fabric mixins or own a live Blaze3D device, so this suite makes
 * no live transformed/GPU draw claim: descriptors are proven against the mapped 26.2 classes,
 * the control seam and the cloud-clock state machine are driven at the same seams the mixin
 * uses, passthrough is proven by the control seam answering false (vanilla keeps control), and
 * the eligible pass setup is executed end to end on a recording fake command backend with
 * injected failures at every fallible point - the payload-buffer allocation, the payload
 * write, the MRT pass creation, the uniform bind, the twin compile, and the pass close -
 * asserting each degrades to the exact vanilla pass (or is absorbed) without throwing. The
 * cloud shader compiles through the same LWJGL Shaderc + spirv-cross path
 * `GlslCompiler` and `IntermediaryShaderModule` use - it inlines the fog include it needs, so
 * unlike the terrain shader it is self-contained - and the reflected output order is pinned to
 * fragColor-then-velocityColor, the order Minecraft's location rewrite turns into color
 * attachments 0 and 1.
 */
class MotionVectorCloudTest {
	private val repository = Path.of("").toAbsolutePath()

	@Test
	fun `cloud pipelines are the mapped CLOUDS and FLAT_CLOUDS pair and the twin preserves source descriptors`() {
		// The two mapped cloud pipelines: same rendertype_clouds shaders, differing only in cull.
		val fancy = RenderPipelines.CLOUDS
		val flat = RenderPipelines.FLAT_CLOUDS
		assertNotSame(fancy, flat)
		assertSame(fancy.vertexShader, flat.vertexShader)
		assertSame(fancy.fragmentShader, flat.fragmentShader)
		assertTrue(fancy.isCull, "CLOUDS culls back faces")
		assertFalse(flat.isCull, "FLAT_CLOUDS renders both faces")
		assertSame(fancy.depthStencilState, flat.depthStencilState)

		// The plain twin (M-4 descriptor contract) and the cloud writer twin on top of it.
		val plain = velocityTwin(fancy)
		val twin = cloudVelocityTwin(plain)

		// The writer twin preserves every source descriptor field through the plain twin.
		assertSame(fancy.vertexShader, twin.vertexShader)
		assertEquals(fancy.shaderDefines, twin.shaderDefines)
		assertSame(fancy.depthStencilState, twin.depthStencilState)
		assertSame(fancy.polygonMode, twin.polygonMode)
		assertEquals(fancy.isCull, twin.isCull)
		assertSame(fancy.primitiveTopology, twin.primitiveTopology)

		// The cloud pipelines carry no vertex format bindings: the geometry comes from the
		// CloudFaces texel buffer and gl_VertexID, so the twin must keep that empty binding set.
		assertEquals(fancy.getVertexFormatBindings()!!.size, twin.getVertexFormatBindings()!!.size)
		for (index in 0 until twin.getVertexFormatBindings()!!.size) {
			assertNull(twin.getVertexFormatBinding(index), "binding $index stays unbound")
		}

		// The source cloud layouts stay (Globals, MatricesProjection, Fog, CloudInfo), exactly
		// one CloudVelocityConfig layout is added.
		assertEquals(fancy.bindGroupLayouts.size + 1, twin.bindGroupLayouts.size)
		for (index in fancy.bindGroupLayouts.indices) {
			assertSame(fancy.bindGroupLayouts[index], twin.bindGroupLayouts[index])
		}
		assertSame(CloudVelocityRender.LAYOUT, twin.bindGroupLayouts.last())

		// Target zero is the source cloud target - translucent blend intact - and target one
		// is exactly the unblended RG16_FLOAT velocity payload.
		val sourceTargets = fancy.colorTargetStates
		val twinTargets = twin.colorTargetStates
		assertEquals(2, twinTargets.size)
		assertSame(sourceTargets[0], twinTargets[0])
		assertEquals(Optional.of(BlendFunction.TRANSLUCENT), twinTargets[0]!!.blendFunction())
		assertVelocityTarget(twinTargets[1]!!)
	}

	@Test
	fun `cloud twin is cached per source and distinct from the other writer twins`() {
		val source = RenderPipelines.CLOUDS
		val plain = velocityTwin(source)

		assertEquals(Identifier.fromNamespaceAndPath("mc-dlss", "velocity/pipeline/clouds"), plain.location)
		assertSame(plain, velocityTwin(source), "the plain twin is cached per source pipeline")

		val twin = cloudVelocityTwin(plain)
		assertSame(twin, cloudVelocityTwin(plain), "the writer twin is cached per plain twin")
		assertEquals(Identifier.fromNamespaceAndPath("mc-dlss", "velocity/cloud/clouds"), twin.location)
		assertEquals(
			Identifier.fromNamespaceAndPath("mc-dlss", "velocity/cloud/flat_clouds"),
			cloudVelocityTwin(velocityTwin(RenderPipelines.FLAT_CLOUDS)).location,
		)

		// The cloud twin lives at its own location: no collision with the plain twin's
		// velocity/pipeline path or the other writer twins of the same source.
		assertNotEquals(plain.location, twin.location)
		assertNotEquals(terrainVelocityTwin(plain).location, twin.location)
		assertNotEquals(entityVelocityTwin(plain).location, twin.location)
		assertNotEquals(weatherVelocityTwin(plain).location, twin.location)
		assertNotEquals(particleVelocityTwin(plain).location, twin.location)
	}

	@Test
	fun `cloud twin keeps the flat variant's cull-off state and admits exactly the cloud shader family`() {
		val twin = cloudVelocityTwin(velocityTwin(RenderPipelines.FLAT_CLOUDS))
		assertEquals(RenderPipelines.FLAT_CLOUDS.isCull, twin.isCull)
		assertFalse(twin.isCull, "the flat variant must keep rendering both faces")

		// The pipeline gate admits exactly the two cloud pipelines' shader family - CLOUDS and
		// FLAT_CLOUDS both bind core/rendertype_clouds - and no other writer's family.
		assertTrue(CloudVelocityRender.isCloudPipeline(RenderPipelines.CLOUDS))
		assertTrue(CloudVelocityRender.isCloudPipeline(RenderPipelines.FLAT_CLOUDS))
		assertFalse(CloudVelocityRender.isCloudPipeline(RenderPipelines.WEATHER_DEPTH_WRITE))
		assertFalse(CloudVelocityRender.isCloudPipeline(RenderPipelines.SOLID_BLOCK))
		assertFalse(CloudVelocityRender.isCloudPipeline(RenderPipelines.CRUMBLING))
	}

	@Test
	fun `cloud pass attachments agree with the twin on both routes`() {
		val scene = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM))
		val velocity = FakeView(FakeTexture(GpuFormat.RG16_FLOAT))

		// The vanilla route: the one-attachment cloud pass binds the source pipeline.
		val oneTarget = RenderPassDescriptor.create({ "Clouds" })
			.withColorAttachment(scene)
			.withDepthAttachment(FakeView(FakeTexture(GpuFormat.D32_FLOAT)), OptionalDouble.empty())
		assertEquals(1, oneTarget.colorAttachments().size)
		assertEquals(RenderPipelines.CLOUDS.colorTargetStates.size, oneTarget.colorAttachments().size)

		// The VELOCITY_MRT route: the two-attachment pass must agree with the two-target twin -
		// exactly the count/format check RenderPass.setPipeline performs on first bind.
		val twin = cloudVelocityTwin(velocityTwin(RenderPipelines.CLOUDS))
		val twoTarget = RenderPassDescriptor.create({ "Clouds velocity" })
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
	fun `cloud shader preserves the cloud color output and writes velocity after the final color`() {
		val shader = cloudShader()

		// The vanilla core/rendertype_clouds fragment body the cloud pipelines bind, verbatim:
		// the vertex color with only the fog alpha fade, no sampling, no fog color blend.
		assertTrue(shader.contains("vec4 color = vertexColor;"))
		assertTrue(shader.contains("color.a *= 1.0f - linear_fog_value(vertexDistance, 0, FogCloudsEnd)"))
		assertTrue(shader.contains("fragColor = color;"))
		assertTrue(shader.contains("in float vertexDistance;"))
		assertTrue(shader.contains("in vec4 vertexColor;"))

		// The inlined vanilla fog include it needs (FogCloudsEnd, linear_fog_value).
		assertTrue(shader.contains("layout(std140) uniform Fog {"))
		assertTrue(shader.contains("float FogCloudsEnd;"))

		// The payload: the cloud writer's own CloudVelocityConfig block and velocity output.
		assertTrue(shader.contains("layout(std140) uniform CloudVelocityConfig {"))
		assertTrue(shader.contains("mat4 ObjectReprojection;"))
		assertTrue(shader.contains("vec4 VelocityParams;"))
		assertTrue(shader.contains("out vec4 velocityColor;"))
		assertTrue(shader.contains("const float INVALID_VELOCITY = 10000.0;"))

		// Assignment order: glslang emits fragment outputs in first-assignment order and
		// Minecraft rewrites locations by that reflection order, so the final fragColor write
		// must precede the velocityColor write or the near-black payload lands on attachment 0.
		val fragWrite = shader.indexOf("fragColor = color;")
		val velocityWrite = shader.indexOf("velocityColor = invalidPixel")
		assertTrue(fragWrite >= 0, "the final fragColor write must exist")
		assertTrue(velocityWrite >= 0, "the velocityColor write must exist")
		assertTrue(velocityWrite > fragWrite, "velocityColor must be assigned after the final fragColor")
	}

	/**
	 * The compiled seam, exercising the true mechanism: the cloud shader is self-contained
	 * (it inlines the fog include instead of #moj_import, which needs Minecraft's resource
	 * preprocessor), so it can be compiled through the same LWJGL Shaderc + spirv-cross path
	 * `GlslCompiler.createIntermediary` and `IntermediaryShaderModule.createFromSpirv` use.
	 * The stage-output reflection list must come back fragColor-first (that list's index is what
	 * the location rewrite writes), and applying the rewrite must leave fragColor on Location 0
	 * (the cloud color target) and velocityColor on Location 1 (the velocity attachment).
	 */
	@Test
	fun `cloud shader reflects outputs in fragColor-then-velocityColor order through Minecraft's compile path`() {
		val spirv = compileFragmentShader(minecraftFragmentSource(cloudShader()))
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
				"after the createFromSpirv rewrite fragColor must sit on attachment 0 (cloud color) and " +
					"velocityColor on attachment 1 (velocity)",
			)
		} finally {
			MemoryUtil.memFree(spirv)
		}
	}

	/**
	 * The cloud shader derives previous NDC from the drift-composed object reprojection, and
	 * the shared classification collapses reset frames and invalid reprojections to the one
	 * representable sentinel instead of the identity-derived zero.
	 */
	@Test
	fun `cloud shader writes the drift-composed reprojection with the exact sentinel on reset`() {
		val shader = cloudShader()
		assertTrue(shader.contains("vec4 clip = vec4(ndc, gl_FragCoord.z, 1.0);"))
		assertTrue(shader.contains("vec4 previous = ObjectReprojection * clip;"))
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

		// The same classification over a reprojection carrying the cloud drift: the camera
		// reprojection with a -0.03 blocks/tick X displacement conjugated in stays finite and
		// below the sentinel, exactly the payload the writer feeds this shader.
		val drift = CloudVelocityRender.driftDisplacement(1.25f)
		val drifted = objectReprojection(
			camera,
			Matrix4f(),
			DlssJitterOffset(0, 0f, 0f, DlssDimensions(1280, 720)),
			drift,
		)
		for (probe in probes) {
			val result = classify(drifted, probe)
			if (result.x == INVALID_VELOCITY) {
				assertEquals(INVALID_VELOCITY, result.y, "a sentinel carries both components at $probe")
			} else {
				assertTrue(result.x == result.x && result.y == result.y, "motion must not be NaN: $result")
				assertTrue(
					abs(result.x) < INVALID_VELOCITY && abs(result.y) < INVALID_VELOCITY,
					"the drift-composed vector stays below the sentinel: $result",
				)
			}
		}
	}

	@Test
	fun `an eligible open velocity-mrt phase offers the cloud writer the scene velocity view`() {
		val phase = phase(velocityRuntime())

		// Closed or not yet opened: no velocity view, the cloud pass stays vanilla.
		assertFalse(CloudVelocityRender.canRedirect(phase))
		assertNull(CloudVelocityRender.velocityView(phase))

		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		assertTrue(CloudVelocityRender.canRedirect(phase))
		val view = checkNotNull(CloudVelocityRender.velocityView(phase))
		assertEquals(GpuFormat.RG16_FLOAT, view.texture().getFormat())
		assertEquals(render.width, view.getWidth(0))
		assertEquals(render.height, view.getHeight(0))

		phase.end()
		assertFalse(CloudVelocityRender.canRedirect(phase))
		assertNull(CloudVelocityRender.velocityView(phase))
	}

	@Test
	fun `vanilla camera-only and non-open phases keep the cloud pass vanilla and cannot throw`() {
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
			assertFalse(CloudVelocityRender.canRedirect(cameraOnlyPhase))
			assertNull(CloudVelocityRender.velocityView(cameraOnlyPhase))
			cameraOnlyPhase.end()
		}

		// Vanilla: a session without DLSS keeps the cloud pass on its exact source route.
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
			assertFalse(CloudVelocityRender.canRedirect(vanillaPhase))
			vanillaPhase.end()
		}
	}

	/**
	 * The cloud writer's own motion: the per-frame cloud-offset delta composed into the camera
	 * reprojection, with the exact reset sentinel on a mesh rebuild, a clock discontinuity, or
	 * a frame without a predecessor.
	 *
	 * The clouds drift at a constant -0.03 blocks per tick of the game clock along X (the
	 * pattern anchor sits at `cell*12 - cloudOffset*0.03`, and cloudOffset advances with the
	 * game clock), so the drift displacement between two rendered frames is exactly the clock
	 * advance: `(gameTime_cur - gameTime_prev) + (partialTicks_cur - partialTicks_prev)`. The
	 * writer holds only that previous clock - no per-cell state - and consumes the mixin's
	 * mesh-rebuild observation, exactly the "cloud offset state only, reset on discontinuity
	 * or mesh rebuild" boundary.
	 */
	@Test
	fun `cloud writer composes the cloud-offset delta with camera reprojection and resets on rebuild and discontinuity`() {
		try {
			// Isolate from any earlier eligible-path execution: the previous-clock state is a
			// singleton, so the first observation must be fresh for the sentinel assertions.
			CloudVelocityRender.resetState()

			// The pure drift math: the offset delta is the game-clock advance, the displacement
			// is that delta at the constant -0.03 blocks/tick drift along X.
			assertEquals(
				1.25f,
				CloudVelocityRender.driftDelta(
					CloudVelocityRender.CloudClock(100L, 0.5f),
					CloudVelocityRender.CloudClock(101L, 0.75f),
				),
				1e-6f,
				"the drift delta is the game-clock advance",
			)
			val displacement = CloudVelocityRender.driftDisplacement(1.25f)
			assertEquals(-0.03f * 1.25f, displacement.x, 1e-6f, "the pattern drifts toward -X at 0.03 blocks/tick")
			assertEquals(0f, displacement.y, 1e-6f)
			assertEquals(0f, displacement.z, 1e-6f)

			// The reprojection classification: the writer's reprojection is exactly the object
			// reprojection with the drift displacement, and the exact reset/unknown-history
			// classification (missing or reset frame, missing view-projection or jitter, or a
			// missing displacement) all mean the invalid sentinel.
			val camera = DlssFrameMotion(Matrix4f(), 1f, 1f, 16f, false)
			val viewProjection = Matrix4f()
			val jitter = DlssJitterOffset(0, 0f, 0f, DlssDimensions(1280, 720))
			assertEquals(
				objectReprojection(camera, viewProjection, jitter, displacement),
				CloudVelocityRender.cloudReprojection(camera, viewProjection, jitter, displacement),
				"the writer's reprojection is exactly the object reprojection with the cloud drift",
			)
			assertNull(CloudVelocityRender.cloudReprojection(null, viewProjection, jitter, displacement))
			assertNull(CloudVelocityRender.cloudReprojection(camera.copy(reset = true), viewProjection, jitter, displacement))
			assertNull(CloudVelocityRender.cloudReprojection(camera, null, jitter, displacement))
			assertNull(CloudVelocityRender.cloudReprojection(camera, viewProjection, null, displacement))
			assertNull(CloudVelocityRender.cloudReprojection(camera, viewProjection, jitter, null))

			// The frame state machine through the production seam, on an open velocity-MRT
			// phase whose camera history is established.
			val runtime = velocityRuntime()
			val phase = phase(runtime)
			renderFrame(phase)
			phase.prepare(true, mainTarget, camera())
			phase.begin(true, mainTarget)

			// Frame one: no previous clock - the sentinel, not the identity-derived zero.
			var payload = CloudVelocityRender.cloudPayload(phase, gameTime = 100L, partialTicks = 0.5f, meshRebuilt = false)
			assertTrue(payload.invalid, "the first observation has no predecessor: the sentinel")

			// Frame two: the clock advanced one tick plus a quarter - the valid drift-composed
			// reprojection, exactly the object reprojection of that 1.25-tick drift.
			payload = CloudVelocityRender.cloudPayload(phase, gameTime = 101L, partialTicks = 0.75f, meshRebuilt = false)
			assertFalse(payload.invalid, "a continuous clock advance composes the drift")
			assertEquals(
				objectReprojection(
					checkNotNull(phase.activeMotion),
					checkNotNull(phase.currentViewProjection),
					checkNotNull(phase.activeJitter),
					CloudVelocityRender.driftDisplacement(1.25f),
				),
				payload.reprojection,
				"the payload reprojection carries exactly the per-frame cloud-offset delta",
			)

			// Frame three: the mixin observed a mesh rebuild - the sentinel for that frame.
			payload = CloudVelocityRender.cloudPayload(phase, gameTime = 102L, partialTicks = 0.25f, meshRebuilt = true)
			assertTrue(payload.invalid, "a mesh rebuild resets the frame to the sentinel")

			// Frame four: the drift continues from the rebuild frame's clock - the rebuild
			// invalidates only its own frame, the state machine keeps measuring.
			payload = CloudVelocityRender.cloudPayload(phase, gameTime = 103L, partialTicks = 0.5f, meshRebuilt = false)
			assertFalse(payload.invalid, "the rebuild resets only the rebuild frame; the clock delta continues")

			// A clock discontinuity (a world change restarts the game clock): the sentinel.
			payload = CloudVelocityRender.cloudPayload(phase, gameTime = 0L, partialTicks = 0f, meshRebuilt = false)
			assertTrue(payload.invalid, "a clock jump beyond the discontinuity bound is the sentinel")
			assertTrue(
				CloudVelocityRender.MAX_CLOCK_JUMP_TICKS > 1f,
				"the discontinuity bound is generous: normal frame advances are ~1 tick",
			)

			// A steady frame after the discontinuity measures from the new clock.
			payload = CloudVelocityRender.cloudPayload(phase, gameTime = 1L, partialTicks = 0.5f, meshRebuilt = false)
			assertFalse(payload.invalid, "the state machine recovers from the discontinuity on the next frame")
			phase.end()
		} finally {
			CloudVelocityRender.resetState()
		}
	}

	@Test
	fun `cloud writer fills the CloudVelocityConfig payload with the drift-composed reprojection`() {
		val writer = source("src/main/kotlin/me/snowmii/dlss/mrt/CloudVelocityRender.kt")
		val objectState = source("src/main/kotlin/me/snowmii/dlss/mrt/ObjectMotionState.kt")

		// The writer's own payload block: one mat4 + one vec4, the same std140 shape the
		// entity/moving-block writers use, under the cloud writer's own uniform name.
		assertTrue(writer.contains("CloudVelocityConfig"))
		assertTrue(writer.contains("putMat4f("))
		assertTrue(writer.contains("putVec4("))
		assertTrue(writer.contains("if (payload.invalid) 1f else 0f"), "the reset flag forces the per-pixel sentinel")
		assertTrue(writer.contains("encoder.writeToBuffer(buffer.slice(), data)"))

		// The drift composes through the existing object-reprojection machinery: the camera's
		// authoritative reprojection with the cloud-offset displacement conjugated in.
		assertTrue(writer.contains("objectReprojection("))
		assertTrue(objectState.contains("fun objectReprojection("))
		assertTrue(writer.contains("phase.activeMotion"))
		assertTrue(writer.contains("phase.currentViewProjection"))
		assertTrue(writer.contains("phase.activeJitter"))
		assertTrue(writer.contains("meshRebuilt"), "the mixin's rebuild observation drives the reset")
		assertTrue(writer.contains("MAX_CLOCK_JUMP_TICKS"), "a clock discontinuity resets the sentinel")
		assertTrue(writer.contains("canRedirect"), "the control seam answers false for ineligible routes")
		assertTrue(writer.contains("uniformSlice()"), "the pass binds the writer's shared payload buffer")
		assertEquals("CloudVelocityConfig", CloudVelocityRender.UNIFORM_NAME)
		assertEquals("core/velocity_clouds", CloudVelocityRender.SHADER_PATH)
		assertEquals(Identifier.fromNamespaceAndPath("mc-dlss", "core/velocity_clouds"), CloudVelocityRender.FRAGMENT_SHADER)
		assertEquals(
			80,
			CloudVelocityRender.UBO_SIZE,
			"the payload is one mat4 plus one vec4",
		)
	}

	@Test
	fun `cloud mixin redirects the mapped cloud render seams and observes the rebuild and cloud clock`() {
		val mixin = source("src/main/java/me/snowmii/dlss/mixin/CloudRendererMotionMixin.java")
		val writer = source("src/main/kotlin/me/snowmii/dlss/mrt/CloudVelocityRender.kt")
		val mixins = source("src/main/resources/mc-dlss.mixins.json")

		// The mapped seam: CloudRenderer.render creates the cloud pass over the clouds/main
		// target, binds the CLOUDS / FLAT_CLOUDS selection, and rebuilds the mesh through
		// MappableRingBuffer.rotate inside the rebuild block.
		assertTrue(mixin.contains("@Mixin(CloudRenderer.class)"))
		assertTrue(mixin.contains("method = \"render\""))
		assertTrue(mixin.contains("CommandEncoder;createRenderPass("))
		assertTrue(mixin.contains("RenderPass;setPipeline("))
		assertTrue(mixin.contains("RenderPass;close()V"), "the owned close seam guards the writer pass's close")
		assertTrue(mixin.contains("MappableRingBuffer;rotate()V"))

		// The redirects are thin delegations into the writer's failure-atomic interception: the
		// pass creation, the pipeline swap, and the owned close all live in CloudVelocityRender,
		// where the test JVM can drive them without a live client.
		assertTrue(mixin.contains("CloudVelocityRender.createPass("))
		assertTrue(mixin.contains("CloudVelocityRender.bindPipeline("))
		assertTrue(mixin.contains("CloudVelocityRender.closePass("))
		assertTrue(mixin.contains("CLOUD_MESH_REBUILT.get()"), "the mixin passes its rebuild observation into the interception")

		// The writer fills the payload on the same encoder the pass is created from, with the
		// exact cloud clock the render call received: levelRenderState.gameTime is the level's
		// game time and LevelRenderer.render passes deltaTracker.getGameTimeDeltaPartialTick(false).
		assertTrue(writer.contains("writeToBuffer(buffer.slice(), data)"))
		assertTrue(writer.contains("level.getGameTime()"))
		assertTrue(writer.contains("getDeltaTracker().getGameTimeDeltaPartialTick(false)"))
		assertTrue(writer.contains("withColorAttachment(velocity, Optional.empty())"))
		// Ineligible routes keep the exact vanilla pass creation and cannot throw.
		assertTrue(writer.contains("terrainVelocityView"))
		assertTrue(writer.contains("encoder.createRenderPass(label, colorTexture, clearColor, depthTexture, clearDepth)"))
		assertTrue(mixins.contains("CloudRendererMotionMixin"))

		// The mapped render method exists with the descriptor the redirects live in.
		val render = CloudRenderer::class.java.getDeclaredMethod(
			"render",
			Int::class.javaPrimitiveType,
			CloudStatus::class.java,
			Float::class.javaPrimitiveType,
			Int::class.javaPrimitiveType,
			Vec3::class.java,
			Long::class.javaPrimitiveType,
			Float::class.javaPrimitiveType,
		)
		assertEquals(Void.TYPE, render.returnType)

		// The untransformed mixin class is a plain object at test runtime.
		val mixinClass = Class.forName("me.snowmii.dlss.mixin.CloudRendererMotionMixin")

		// The render-head handler clears the rebuild observation before every render call.
		val headHandler = mixinClass.getDeclaredMethod(
			"mcDlssCloudRenderHead",
			Int::class.javaPrimitiveType,
			CloudStatus::class.java,
			Float::class.javaPrimitiveType,
			Int::class.javaPrimitiveType,
			Vec3::class.java,
			Long::class.javaPrimitiveType,
			Float::class.javaPrimitiveType,
			CallbackInfo::class.java,
		)
		val headInject = requireNotNull(headHandler.getAnnotation(Inject::class.java))
		assertTrue(headInject.method.contentEquals(arrayOf("render")))
		assertEquals("HEAD", headInject.at.first().value)

		// The rebuild handler observes the one rotate() call inside render - the mesh rebuild
		// block - and passes it through.
		val rebuildHandler = mixinClass.getDeclaredMethod("mcDlssCloudMeshRebuilt", MappableRingBuffer::class.java)
		val rebuildRedirect = requireNotNull(rebuildHandler.getAnnotation(Redirect::class.java))
		assertTrue(rebuildRedirect.method.contentEquals(arrayOf("render")))
		assertEquals("INVOKE", rebuildRedirect.at.value)
		assertEquals(
			"Lnet/minecraft/client/renderer/MappableRingBuffer;rotate()V",
			rebuildRedirect.at.target,
		)

		// The pass-creation handler's @Redirect matches the mapped CommandEncoder descriptor.
		val passHandler = mixinClass.getDeclaredMethod(
			"mcDlssCloudRenderPass",
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
			"mcDlssCloudSetPipeline",
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

		// The owned close handler's @Redirect matches the mapped RenderPass close the source
		// render's try-with-resources invokes.
		val closeHandler = mixinClass.getDeclaredMethod("mcDlssCloudClose", RenderPass::class.java)
		val closeRedirect = requireNotNull(closeHandler.getAnnotation(Redirect::class.java))
		assertTrue(closeRedirect.method.contentEquals(arrayOf("render")))
		assertEquals("INVOKE", closeRedirect.at.value)
		assertEquals(
			"Lcom/mojang/blaze3d/systems/RenderPass;close()V",
			closeRedirect.at.target,
		)
	}

	/**
	 * Executes the eligible production pass setup end to end on a recording fake command
	 * backend: the writer's own gates, preflight (payload write, buffer allocation, twin
	 * precompile), the two-attachment pass descriptor, the CloudVelocityConfig uniform bind,
	 * the twin swap at the pipeline boundary, and the owned close all run for real, with only
	 * the device faked through the writer's seams. The recorded sequence is the contract: the
	 * source color target at attachment 0, the scene-sized velocity view at attachment 1, the
	 * payload block written on the pass's encoder, the preflighted cloud writer twin accepted
	 * against those attachments by the real `RenderPass.setPipeline` validation, and the pass
	 * closed exactly once by the owned close seam.
	 */
	@Test
	fun `eligible cloud pass setup executes on a fake command backend and records the full replacement`() {
		val runtime = velocityRuntime()
		val phase = phase(runtime)
		try {
			renderFrame(phase)
			phase.prepare(true, mainTarget, camera())
			phase.begin(true, mainTarget)
			val velocityView = checkNotNull(phase.terrainVelocityView)

			// Drop the writer's cached payload allocation so this backend's buffer is the one
			// the preflight allocates and the pass binds.
			CloudVelocityRender.resetState()

			val backend = CloudFakeBackend()
			CloudVelocityRender.activePhaseOverride = phase
			CloudVelocityRender.cloudClockProvider = { CloudVelocityRender.CloudClock(100L, 0.5f) }
			CloudVelocityRender.deviceProvider = { backend.device }

			val colorView = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM, render.width, render.height))
			val depthView = FakeView(FakeTexture(GpuFormat.D32_FLOAT, render.width, render.height))
			val pass = CloudVelocityRender.createPass(
				backend.encoder,
				{ "Clouds" },
				colorView,
				Optional.empty(),
				depthView,
				OptionalDouble.empty(),
				meshRebuilt = false,
			)

			// The two-attachment pass: the source color at index 0, the scene-sized RG16_FLOAT
			// velocity view at index 1, rendered over the scene's size, with the source depth.
			val descriptor = checkNotNull(backend.renderPassDescriptors.lastOrNull())
			val attachments = descriptor.colorAttachments()
			assertEquals(2, attachments.size)
			assertSame(colorView, attachments[0]!!.textureView())
			assertSame(velocityView, attachments[1]!!.textureView())
			assertTrue(attachments[1]!!.clearValue().isEmpty(), "the velocity attachment is never cleared")
			assertEquals(render.width, checkNotNull(descriptor.renderArea).width())
			assertEquals(render.height, checkNotNull(descriptor.renderArea).height())
			assertEquals(GpuFormat.RG16_FLOAT, attachments[1]!!.textureView().texture().getFormat())

			// The payload: this frame's CloudVelocityConfig block was written through the fake
			// encoder, then bound under the writer's uniform name at pass creation.
			assertEquals(CloudVelocityRender.UBO_SIZE, backend.payloadBytes, "the payload write carries the full block")
			assertEquals(listOf(CloudVelocityRender.UNIFORM_NAME), backend.uniforms.map { it.first })
			assertSame(backend.payloadBuffer, backend.uniforms.single().second.buffer())
			assertEquals(0L, backend.uniforms.single().second.offset(), "the bound slice is offset-zero, valid for any alignment")

			// The preflight precompiled both cloud twins on the writer's device, so the bind is
			// a cache hit on a validated pipeline.
			assertEquals(2, backend.precompiledPipelines.size, "both cloud statics' twins were precompiled")
			assertTrue(backend.precompiledPipelines.contains(cloudVelocityTwin(velocityTwin(RenderPipelines.CLOUDS))))
			assertTrue(backend.precompiledPipelines.contains(cloudVelocityTwin(velocityTwin(RenderPipelines.FLAT_CLOUDS))))

			// The pipeline-boundary swap binds the preflighted twin, and the real
			// RenderPass.setPipeline validation accepted its two targets against the attachments.
			CloudVelocityRender.bindPipeline(pass, RenderPipelines.CLOUDS)
			assertSame(cloudVelocityTwin(velocityTwin(RenderPipelines.CLOUDS)), backend.pipeline)

			// The owned close seam closed the pass exactly once and dropped the latch.
			CloudVelocityRender.closePass(pass)
			assertEquals(1, backend.passCloses)
			assertFalse(CloudVelocityRender.isLatched(pass), "the close seam drops the latch")
		} finally {
			resetSeams()
			if (phase.isOpen) phase.end()
		}
	}

	/**
	 * Injects a failure at every fallible point of the eligible pass setup and asserts the
	 * interception answers the exact vanilla one-attachment pass - the source pipeline binds
	 * into it unchanged - without ever throwing out of the writer.
	 *
	 * The failure classes are the ones the review named: the payload-buffer allocation, the
	 * payload write, the MRT pass creation, and the uniform bind. Each is preflighted or
	 * guarded before the pass is handed to the source render, so a failure can never leave the
	 * source render with a two-attachment pass its one-target pipeline cannot bind.
	 */
	@Test
	fun `eligible cloud pass setup preflights allocation write pass-creation and uniform-bind failures to the exact vanilla pass`() {
		val runtime = velocityRuntime()
		val phase = phase(runtime)
		try {
			renderFrame(phase)
			phase.prepare(true, mainTarget, camera())
			phase.begin(true, mainTarget)
			CloudVelocityRender.activePhaseOverride = phase
			CloudVelocityRender.cloudClockProvider = { CloudVelocityRender.CloudClock(100L, 0.5f) }

			val colorView = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM, render.width, render.height))
			val depthView = FakeView(FakeTexture(GpuFormat.D32_FLOAT, render.width, render.height))

			for (failurePoint in listOf("createBuffer", "writeToBuffer", "createRenderPass", "setUniform")) {
				// Drop the writer's cached payload allocation so the createBuffer injection is
				// actually consulted; the cache is a singleton, so a prior successful allocation
				// would otherwise mask it. The createRenderPass injection fails only the MRT
				// creation - the vanilla fallback creation must still succeed to prove the guard -
				// so that case uses the once-flag and leaves the persistent failAt clear.
				CloudVelocityRender.resetState()
				val backend = CloudFakeBackend().also {
					it.failAt = if (failurePoint == "createRenderPass") null else failurePoint
					it.failCreateRenderPassOnce = failurePoint == "createRenderPass"
				}
				CloudVelocityRender.deviceProvider = { backend.device }

				val pass = CloudVelocityRender.createPass(
					backend.encoder,
					{ "Clouds" },
					colorView,
					Optional.empty(),
					depthView,
					OptionalDouble.empty(),
					meshRebuilt = false,
				)

				// The exact vanilla fallback: one attachment, and the source pipeline binds into
				// it through the non-latched branch of the pipeline-boundary seam.
				assertEquals(1, checkNotNull(backend.renderPassDescriptors.lastOrNull()).colorAttachments().size, "$failurePoint: the fallback pass keeps one attachment")
				assertFalse(CloudVelocityRender.isLatched(pass), "$failurePoint: the failed eligible path never latches the pass")
				assertDoesNotThrow {
					CloudVelocityRender.bindPipeline(pass, RenderPipelines.CLOUDS)
				}
				assertSame(RenderPipelines.CLOUDS, backend.pipeline, "$failurePoint: the source pipeline binds unchanged on the fallback")
			}
		} finally {
			resetSeams()
			if (phase.isOpen) phase.end()
		}
	}

	/**
	 * The twin/pipeline-bind failure point is the lazy shader compilation the first bind
	 * would trigger; the interception surfaces it at the preflight's device precompile, so a
	 * compile throw or an invalid compiled twin degrades to the exact vanilla pass instead of
	 * throwing at the bind, where no recovery exists.
	 */
	@Test
	fun `eligible cloud pass setup preflights twin compilation and validity failures to the exact vanilla pass`() {
		val runtime = velocityRuntime()
		val phase = phase(runtime)
		try {
			renderFrame(phase)
			phase.prepare(true, mainTarget, camera())
			phase.begin(true, mainTarget)
			CloudVelocityRender.activePhaseOverride = phase
			CloudVelocityRender.cloudClockProvider = { CloudVelocityRender.CloudClock(100L, 0.5f) }

			val colorView = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM, render.width, render.height))
			val depthView = FakeView(FakeTexture(GpuFormat.D32_FLOAT, render.width, render.height))

			for (case in listOf("precompile-throw", "precompile-invalid")) {
				CloudVelocityRender.resetState()
				val backend = CloudFakeBackend().also {
					it.failAt = if (case == "precompile-throw") "precompilePipeline" else null
					it.precompileInvalid = case == "precompile-invalid"
				}
				CloudVelocityRender.deviceProvider = { backend.device }

				assertDoesNotThrow {
					val pass = CloudVelocityRender.createPass(
						backend.encoder,
						{ "Clouds" },
						colorView,
						Optional.empty(),
						depthView,
						OptionalDouble.empty(),
						meshRebuilt = false,
					)
					assertEquals(1, checkNotNull(backend.renderPassDescriptors.lastOrNull()).colorAttachments().size, "$case: the fallback pass keeps one attachment")
					assertFalse(CloudVelocityRender.isLatched(pass), "$case: the failed eligible path never latches the pass")
					CloudVelocityRender.bindPipeline(pass, RenderPipelines.CLOUDS)
					assertSame(RenderPipelines.CLOUDS, backend.pipeline, "$case: the source pipeline binds unchanged")
				}
			}
		} finally {
			resetSeams()
			if (phase.isOpen) phase.end()
		}
	}

	/**
	 * The realistic pass-creation rejection the two-attachment descriptor can hit that the
	 * one-attachment pass cannot: an attachment size mismatch. The preflight validates the
	 * velocity view against the source color target before the encoder ever sees the
	 * descriptor, so the exact vanilla pass is created and nothing throws.
	 */
	@Test
	fun `eligible cloud pass setup rejects a size-mismatched velocity attachment as the exact vanilla fallback`() {
		val runtime = velocityRuntime()
		val phase = phase(runtime)
		try {
			renderFrame(phase)
			phase.prepare(true, mainTarget, camera())
			phase.begin(true, mainTarget)
			CloudVelocityRender.activePhaseOverride = phase
			CloudVelocityRender.cloudClockProvider = { CloudVelocityRender.CloudClock(100L, 0.5f) }

			val backend = CloudFakeBackend()
			CloudVelocityRender.deviceProvider = { backend.device }
			val colorView = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM, render.width, render.height))
			val depthView = FakeView(FakeTexture(GpuFormat.D32_FLOAT, render.width, render.height))
			val mismatchedVelocity = FakeView(FakeTexture(GpuFormat.RG16_FLOAT, 640, 480))

			// The guard itself: the size-mismatched velocity view is rejected before the
			// encoder is touched, answering false without throwing.
			assertDoesNotThrow {
				assertFalse(
					CloudVelocityRender.prepare(backend.encoder, phase, mismatchedVelocity, colorView, meshRebuilt = false),
					"a velocity view whose size differs from the color target is a preflight failure",
				)
			}
			assertTrue(backend.renderPassDescriptors.isEmpty(), "the rejected descriptor never reaches the encoder")

			// A matching view passes, so the mismatch alone - not the route - drives the fallback.
			assertDoesNotThrow {
				assertTrue(
					CloudVelocityRender.prepare(backend.encoder, phase, checkNotNull(phase.terrainVelocityView), colorView, meshRebuilt = false),
					"a scene-sized velocity view passes the preflight",
				)
			}
		} finally {
			resetSeams()
			if (phase.isOpen) phase.end()
		}
	}

	/**
	 * A device-level close failure on the writer's pass is absorbed by the owned close seam:
	 * the pass still closed (the encoder's in-pass flag was already cleared), the latch is
	 * dropped, and nothing throws. The pass that follows the failed close is the source's
	 * own: the seam only ever guards the latched pass.
	 */
	@Test
	fun `eligible cloud pass close failure is absorbed and the latch is dropped`() {
		val runtime = velocityRuntime()
		val phase = phase(runtime)
		try {
			renderFrame(phase)
			phase.prepare(true, mainTarget, camera())
			phase.begin(true, mainTarget)
			CloudVelocityRender.activePhaseOverride = phase
			CloudVelocityRender.cloudClockProvider = { CloudVelocityRender.CloudClock(100L, 0.5f) }
			CloudVelocityRender.resetState()

			val backend = CloudFakeBackend().also { it.failAt = "passClose" }
			CloudVelocityRender.deviceProvider = { backend.device }
			val colorView = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM, render.width, render.height))
			val depthView = FakeView(FakeTexture(GpuFormat.D32_FLOAT, render.width, render.height))

			val pass = CloudVelocityRender.createPass(
				backend.encoder,
				{ "Clouds" },
				colorView,
				Optional.empty(),
				depthView,
				OptionalDouble.empty(),
				meshRebuilt = false,
			)
			CloudVelocityRender.bindPipeline(pass, RenderPipelines.CLOUDS)

			// The close ran (the pass lifecycle completed) but its device-level failure was
			// absorbed: no throw, and the latch is gone.
			assertDoesNotThrow { CloudVelocityRender.closePass(pass) }
			assertEquals(1, backend.passCloses, "the pass closed exactly once, through the owned seam")
			assertFalse(CloudVelocityRender.isLatched(pass), "the close seam drops the latch even on a failed close")
			assertSame(cloudVelocityTwin(velocityTwin(RenderPipelines.CLOUDS)), backend.pipeline)
		} finally {
			resetSeams()
			if (phase.isOpen) phase.end()
		}
	}

	/**
	 * An unexpected preflight throw - the phase read, the clock read, or any other read the
	 * interception makes - degrades to the exact vanilla pass: nothing the writer does can
	 * throw out of the pass-creation redirect.
	 */
	@Test
	fun `unexpected eligible preflight throw degrades to the exact vanilla pass`() {
		val runtime = velocityRuntime()
		val phase = phase(runtime)
		try {
			renderFrame(phase)
			phase.prepare(true, mainTarget, camera())
			phase.begin(true, mainTarget)
			CloudVelocityRender.activePhaseOverride = phase
			CloudVelocityRender.cloudClockProvider = { throw IllegalStateException("injected clock failure") }
			CloudVelocityRender.resetState()

			val backend = CloudFakeBackend()
			CloudVelocityRender.deviceProvider = { backend.device }
			val colorView = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM, render.width, render.height))

			assertDoesNotThrow {
				val pass = CloudVelocityRender.createPass(
					backend.encoder,
					{ "Clouds" },
					colorView,
					Optional.empty(),
					null,
					OptionalDouble.empty(),
					meshRebuilt = false,
				)
				assertEquals(1, checkNotNull(backend.renderPassDescriptors.lastOrNull()).colorAttachments().size)
				CloudVelocityRender.bindPipeline(pass, RenderPipelines.CLOUDS)
				assertSame(RenderPipelines.CLOUDS, backend.pipeline, "the source pipeline binds unchanged")
			}
		} finally {
			resetSeams()
			if (phase.isOpen) phase.end()
		}
	}

	private fun resetSeams() {
		CloudVelocityRender.activePhaseOverride = null
		CloudVelocityRender.cloudClockProvider = {
			runCatching {
				val minecraft = net.minecraft.client.Minecraft.getInstance()
				if (minecraft == null) null else CloudVelocityRender.CloudClock(0L, 0f)
			}.getOrNull()
		}
		CloudVelocityRender.deviceProvider = { RenderSystem.getDevice() }
	}

	@Test
	fun `cloud twin is registered and reachable from the mixin through the variant surface`() {
		val variant = source("src/main/kotlin/me/snowmii/dlss/mrt/VelocityPipelineVariant.kt")
		assertTrue(variant.contains("fun cloudVelocityTwin(plainTwin: RenderPipeline)"))
		assertTrue(variant.contains("cloudVelocityTwins.computeIfAbsent"))
		assertTrue(variant.contains("withFragmentShader(CloudVelocityRender.FRAGMENT_SHADER)"))
		assertTrue(variant.contains("withBindGroupLayout(CloudVelocityRender.LAYOUT)"))

		// The registered JSON entry is the compile-time seam: a misspelled class name would
		// fail the mixin application in the client.
		val registered = JsonParser.parseString(source("src/main/resources/mc-dlss.mixins.json"))
			.asJsonObject
			.getAsJsonArray("client")
			.map { it.asString }
		assertTrue("CloudRendererMotionMixin" in registered)
	}

	private fun source(path: String) = repository.resolve(path).readText()

	private fun cloudShader(): String = repository
		.resolve("src/main/resources/assets/mc-dlss/shaders/core/velocity_clouds.fsh")
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
				val filenameBuffer = MemoryUtil.memUTF8("velocity_clouds.fsh")
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

	/**
	 * The recording fake command backend the eligible cloud pass setup executes on. The
	 * writer's three seams - the phase, the clock, and the device - point at this fixture;
	 * everything else in `CloudVelocityRender.createPass`/`bindPipeline`/`closePass` runs for
	 * real, including the descriptor build, the twin cache lookup, the real `RenderPass`
	 * constructor, and the real `RenderPass.setUniform`/`setPipeline`/`close` validation
	 * against the fake backends. [failAt] names one device call to make throw for the injected
	 * fallback evidence; [failCreateRenderPassOnce] fails only the next pass creation (the MRT
	 * attempt), so the vanilla fallback creation still succeeds; [precompileInvalid] makes the
	 * fake precompile answer an invalid compiled pipeline.
	 */
	private class CloudFakeBackend {
		var failAt: String? = null
		var failCreateRenderPassOnce = false
		var precompileInvalid = false

		val payloadBuffer = FakeBuffer()
		val renderPassDescriptors = mutableListOf<RenderPassDescriptor>()
		var pipeline: RenderPipeline? = null
		val uniforms = mutableListOf<Pair<String, GpuBufferSlice>>()
		var payloadBytes: Int = -1
		var passCloses = 0
		val precompiledPipelines = mutableListOf<RenderPipeline>()

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
		private val deviceBackend = CloudGpuDeviceBackend(this, deviceInfo)
		private val passBackend = CloudPassBackend(this)
		private val commandBackend = CloudCommandEncoderBackend()

		val encoder: CommandEncoder = CloudEncoder(this, deviceBackend, passBackend, commandBackend)
		val device: GpuDevice = CloudDevice(this, encoder, deviceBackend)

		fun failIf(point: String) {
			if (failAt == point) {
				throw IllegalStateException("injected $point failure")
			}
		}
	}

	/** The fake device: the payload buffer, and the precompile that stands in for the twin bind's lazy compile. */
	private class CloudDevice(
		private val backend: CloudFakeBackend,
		private val encoder: CommandEncoder,
		deviceBackend: GpuDeviceBackend,
	) : GpuDevice(deviceBackend, {}) {
		override fun createCommandEncoder(): CommandEncoder = encoder

		override fun createBuffer(label: Supplier<String>?, usage: Int, size: Long): GpuBuffer {
			backend.failIf("createBuffer")
			return backend.payloadBuffer
		}

		override fun precompilePipeline(pipeline: RenderPipeline, shaderSource: ShaderSource?): CompiledRenderPipeline {
			backend.failIf("precompilePipeline")
			backend.precompiledPipelines.add(pipeline)
			return object : CompiledRenderPipeline {
				override fun isValid(): Boolean = !backend.precompileInvalid
			}
		}
	}

	/**
	 * The recording encoder: records the pass descriptors (the attachment-order evidence) and
	 * the payload write, then builds the real [RenderPass] over the recording backends so the
	 * writer's pass body runs against real validation. The MRT-creation failure injection
	 * fails exactly one creation (the two-attachment attempt), so the vanilla fallback
	 * creation still runs - that is the guard under test.
	 */
	private class CloudEncoder(
		private val backend: CloudFakeBackend,
		private val deviceBackend: GpuDeviceBackend,
		private val passBackend: RenderPassBackend,
		commandBackend: CommandEncoderBackend,
	) : CommandEncoder(null, deviceBackend, commandBackend) {
		override fun createRenderPass(descriptor: RenderPassDescriptor): RenderPass {
			// The MRT-creation injection fails exactly one creation (the two-attachment
			// attempt), so the vanilla fallback creation still runs - that is the guard under
			// test. The failAt injection below is for every other call.
			if (backend.failCreateRenderPassOnce) {
				backend.failCreateRenderPassOnce = false
				throw IllegalStateException("injected createRenderPass failure")
			}
			backend.failIf("createRenderPass")
			backend.renderPassDescriptors.add(descriptor)
			return RenderPass(
				passBackend,
				deviceBackend,
				descriptor.colorAttachments(),
				{
					backend.passCloses++
					backend.failIf("passClose")
				},
				descriptor.renderArea,
			)
		}

		override fun writeToBuffer(destination: GpuBufferSlice, data: ByteBuffer) {
			backend.failIf("writeToBuffer")
			backend.payloadBytes = data.remaining()
		}
	}

	/** Records the pass-body calls the writer makes; every failure point can be injected. */
	private class CloudPassBackend(private val backend: CloudFakeBackend) : RenderPassBackend {
		override fun pushDebugGroup(label: Supplier<String>) = Unit

		override fun popDebugGroup() = Unit

		override fun setPipeline(pipeline: RenderPipeline) {
			backend.failIf("setPipeline")
			backend.pipeline = pipeline
		}

		override fun bindTexture(name: String, textureView: GpuTextureView?, sampler: GpuSampler?) = Unit

		override fun setUniform(name: String, value: GpuBuffer) = setUniform(name, GpuBufferSlice(value, 0, value.size()))

		override fun setUniform(name: String, value: GpuBufferSlice) {
			backend.failIf("setUniform")
			backend.uniforms.add(name to value)
		}

		override fun enableScissor(x: Int, y: Int, width: Int, height: Int) = Unit

		override fun disableScissor() = Unit

		override fun setVertexBuffer(slot: Int, vertexBuffer: GpuBufferSlice?) = Unit

		override fun setIndexBuffer(indexBuffer: GpuBuffer, indexType: IndexType) = Unit

		override fun drawIndexed(indexCount: Int, instanceCount: Int, firstIndex: Int, vertexOffset: Int, firstInstance: Int) = Unit

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
	private class CloudCommandEncoderBackend : CommandEncoderBackend {
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
	private class CloudGpuDeviceBackend(private val backend: CloudFakeBackend, private val info: DeviceInfo) : GpuDeviceBackend {
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

	private class FakeBuffer : GpuBuffer(GpuBuffer.USAGE_VERTEX, 0) {
		override fun isClosed() = false
		override fun close() = Unit
		override fun map(offset: Long, length: Long, read: Boolean, write: Boolean): GpuBufferSlice.MappedView =
			throw UnsupportedOperationException("test buffer is never mapped")
	}

	private companion object {
		/** SPIR-V DecorationLocation, the decoration `createFromSpirv` rewrites and this suite reads back. */
		const val LOCATION_DECORATION = 30

		/** The shared velocity payload's sentinel, mirrored so the JVM classification asserts the same value. */
		const val INVALID_VELOCITY = 10000f

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
	}
}
