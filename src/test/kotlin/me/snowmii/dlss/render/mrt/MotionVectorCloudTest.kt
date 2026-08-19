package me.snowmii.dlss.render.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.buffers.GpuFence
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline
import com.mojang.blaze3d.pipeline.RenderPipeline
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
import java.util.Optional
import java.util.OptionalDouble
import java.util.function.Supplier
import me.snowmii.streamline.Dimensions
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitterOffset
import me.snowmii.dlss.render.mrt.CloudVelocityRender
import me.snowmii.dlss.render.mrt.VelocityWriter
import me.snowmii.dlss.render.mrt.cloudSceneDepthTwin
import me.snowmii.dlss.render.mrt.objectReprojection
import me.snowmii.dlss.render.mrt.writerTwin
import net.minecraft.client.renderer.RenderPipelines
import org.joml.Matrix4f
import org.joml.Vector4fc
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.PointerBuffer

/**
 * Cloud velocity writer.
 *
 * `CloudRenderer.render` creates one pass over the clouds target (or the main target without
 * the transparency chain) with `RenderPipelines.CLOUDS` or `RenderPipelines.FLAT_CLOUDS` and
 * draws the CPU-baked cloud cells through the `CloudFaces` texel buffer,
 * `CloudInfo`/`DynamicTransforms` uniforms, and a QUADS index draw. While an open VELOCITY_MRT
 * world phase offers the scene velocity view, the pass gets the scene-sized RG16_FLOAT
 * velocity attachment at color index 1, the pipeline-boundary seam swaps in a cached cloud
 * twin that preserves the source cloud descriptors (translucent blended target zero, no
 * vertex format bindings - the geometry comes from CloudFaces and gl_VertexID - quads, depth,
 * and the flat variant's cull-off state) plus the mc-dlss cloud velocity fragment shader and
 * the CloudVelocityConfig layout, and the writer fills that payload with this frame's
 * cloud-offset drift composed into the camera reprojection - the invalid sentinel on a mesh
 * rebuild, a clock discontinuity, a reset frame, or a frame without a predecessor. Vanilla
 * and CAMERA_ONLY routes keep the source pass: the control seam answers false and the
 * redirect falls through to the vanilla one-attachment creation.
 *
 * The test JVM does not apply Fabric mixins or own a live Blaze3D device, so this suite makes
 * no live transformed/GPU draw claim. Eligible pass setup runs on a recording fake command
 * backend with injected failures at every fallible point - payload-buffer allocation, payload
 * write, MRT pass creation, uniform bind, twin compile, and pass close - and each degrades to
 * the vanilla pass (or is absorbed) without throwing.
 */
class MotionVectorCloudTest {

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
			val jitter = DlssJitterOffset(0, 0f, 0f, Dimensions(1280, 720))
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
			val phase = velocityWorldPhase(runtime)
			renderFrame(phase, mainTarget)
			phase.prepare(true, mainTarget, cameraSample())
			phase.begin(true, mainTarget)

			// Frame one: no previous clock - the sentinel, not the identity-derived zero.
			var payload = CloudVelocityRender.buildCloudVelocityPayload(phase, gameTime = 100L, partialTicks = 0.5f, meshRebuilt = false)
			assertTrue(payload.invalid, "the first observation has no predecessor: the sentinel")

			// Frame two: the clock advanced one tick plus a quarter - the valid drift-composed
			// reprojection, exactly the object reprojection of that 1.25-tick drift.
			payload = CloudVelocityRender.buildCloudVelocityPayload(phase, gameTime = 101L, partialTicks = 0.75f, meshRebuilt = false)
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
			payload = CloudVelocityRender.buildCloudVelocityPayload(phase, gameTime = 102L, partialTicks = 0.25f, meshRebuilt = true)
			assertTrue(payload.invalid, "a mesh rebuild resets the frame to the sentinel")

			// Frame four: the drift continues from the rebuild frame's clock - the rebuild
			// invalidates only its own frame, the state machine keeps measuring.
			payload = CloudVelocityRender.buildCloudVelocityPayload(phase, gameTime = 103L, partialTicks = 0.5f, meshRebuilt = false)
			assertFalse(payload.invalid, "the rebuild resets only the rebuild frame; the clock delta continues")

			// A clock discontinuity (a world change restarts the game clock): the sentinel.
			payload = CloudVelocityRender.buildCloudVelocityPayload(phase, gameTime = 0L, partialTicks = 0f, meshRebuilt = false)
			assertTrue(payload.invalid, "a clock jump beyond the discontinuity bound is the sentinel")

			// A steady frame after the discontinuity measures from the new clock.
			payload = CloudVelocityRender.buildCloudVelocityPayload(phase, gameTime = 1L, partialTicks = 0.5f, meshRebuilt = false)
			assertFalse(payload.invalid, "the state machine recovers from the discontinuity on the next frame")

			// The bound is generous rather than frame-tight: an advance of several ticks - a
			// slow frame, not a world change - still composes a drift instead of resetting.
			val underBound = CloudVelocityRender.MAX_CLOCK_JUMP_TICKS.toLong() - 1L
			payload = CloudVelocityRender.buildCloudVelocityPayload(
				phase,
				gameTime = 1L + underBound,
				partialTicks = 0.5f,
				meshRebuilt = false,
			)
			assertFalse(payload.invalid, "an advance under the discontinuity bound composes rather than resetting")
			phase.end()
		} finally {
			CloudVelocityRender.resetState()
		}
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
		val phase = velocityWorldPhase(runtime)
		try {
			renderFrame(phase, mainTarget)
			phase.prepare(true, mainTarget, cameraSample())
			phase.begin(true, mainTarget)
			val velocityView = checkNotNull(phase.terrainVelocityView)

			// Drop the writer's cached payload allocation so this backend's buffer is the one
			// the preflight allocates and the pass binds.
			CloudVelocityRender.resetState()

			val backend = CloudFakeBackend()
			CloudVelocityRender.testPhaseOverride = phase
			CloudVelocityRender.currentCloudClock = { CloudVelocityRender.CloudClock(100L, 0.5f) }
			CloudVelocityRender.deviceProvider = { backend.device }

			val colorView = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM, RENDER_DIMENSIONS.width, RENDER_DIMENSIONS.height))
			val depthView = checkNotNull(phase.sceneDepthView)
			val pass = CloudVelocityRender.createCloudVelocityPass(
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
			assertTrue(attachments[1]!!.clearValue().isEmpty, "the velocity attachment is never cleared")
			assertEquals(RENDER_DIMENSIONS.width, checkNotNull(descriptor.renderArea).width())
			assertEquals(RENDER_DIMENSIONS.height, checkNotNull(descriptor.renderArea).height())
			assertEquals(GpuFormat.RG16_FLOAT, attachments[1]!!.textureView().texture().format)

			// The payload: this frame's CloudVelocityConfig block was written through the fake
			// encoder, then bound under the writer's uniform name at pass creation.
			assertEquals(CloudVelocityRender.UBO_SIZE, backend.payloadBytes, "the payload write carries the full block")
			assertEquals(listOf(CloudVelocityRender.UNIFORM_NAME), backend.uniforms.map { it.first })
			assertSame(backend.payloadBuffer, backend.uniforms.single().second.buffer())
			assertEquals(0L, backend.uniforms.single().second.offset(), "the bound buffer view starts at offset zero, valid for any alignment")

			// The preflight precompiled both cloud twins on the writer's device, so the bind is
			// a cache hit on a validated pipeline.
			assertEquals(4, backend.precompiledPipelines.size, "both cloud statics' writer and scene-depth twins were precompiled")
			assertTrue(backend.precompiledPipelines.contains(writerTwin(RenderPipelines.CLOUDS, VelocityWriter.CLOUD)))
			assertTrue(backend.precompiledPipelines.contains(writerTwin(RenderPipelines.FLAT_CLOUDS, VelocityWriter.CLOUD)))
			assertTrue(backend.precompiledPipelines.contains(cloudSceneDepthTwin(RenderPipelines.CLOUDS)))
			assertTrue(backend.precompiledPipelines.contains(cloudSceneDepthTwin(RenderPipelines.FLAT_CLOUDS)))

			// The pipeline-boundary swap binds the preflighted twin, and the real
			// RenderPass.setPipeline validation accepted its two targets against the attachments.
			CloudVelocityRender.bindCloudPipeline(pass, RenderPipelines.CLOUDS)
			assertSame(writerTwin(RenderPipelines.CLOUDS, VelocityWriter.CLOUD), backend.pipeline)

			// The owned close seam closed the pass exactly once and dropped the latch.
			CloudVelocityRender.closeCloudVelocityPass(pass)
			assertEquals(1, backend.passCloses)
			assertFalse(CloudVelocityRender.isLatched(pass), "the close seam drops the latch")
		} finally {
			resetSeams()
			if (phase.isOpen) phase.end()
		}
	}

	/**
	 * Fabulous clouds render into a separate target whose depth is cleared, so testing against
	 * that depth lets cloud motion overwrite terrain in front of the clouds. The writer must
	 * depth-test against the scene instead, without writing that borrowed depth.
	 */
	@Test
	fun `fabulous clouds depth-test against the scene so cloud motion cannot write through terrain`() {
		val runtime = velocityRuntime()
		val phase = velocityWorldPhase(runtime)
		try {
			renderFrame(phase, mainTarget)
			phase.prepare(true, mainTarget, cameraSample())
			phase.begin(true, mainTarget)
			CloudVelocityRender.resetState()

			val backend = CloudFakeBackend()
			CloudVelocityRender.testPhaseOverride = phase
			CloudVelocityRender.currentCloudClock = { CloudVelocityRender.CloudClock(100L, 0.5f) }
			CloudVelocityRender.deviceProvider = { backend.device }

			val cloudsColor = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM, RENDER_DIMENSIONS.width, RENDER_DIMENSIONS.height))
			val cloudsDepth = FakeView(FakeTexture(GpuFormat.D32_FLOAT, RENDER_DIMENSIONS.width, RENDER_DIMENSIONS.height))
			val sceneDepth = checkNotNull(phase.sceneDepthView)
			val pass = CloudVelocityRender.createCloudVelocityPass(
				backend.encoder,
				{ "Clouds" },
				cloudsColor,
				Optional.empty(),
				cloudsDepth,
				OptionalDouble.empty(),
				meshRebuilt = false,
			)

			val descriptor = checkNotNull(backend.renderPassDescriptors.lastOrNull())
			assertSame(
				sceneDepth,
				checkNotNull(descriptor.depthAttachment()).textureView(),
				"the pass depth-tests against the scene, not the cleared clouds-target depth",
			)
			assertTrue(checkNotNull(descriptor.depthAttachment()).clearValue().isEmpty, "borrowed scene depth is never cleared")

			CloudVelocityRender.bindCloudPipeline(pass, RenderPipelines.CLOUDS)
			val occlude = cloudSceneDepthTwin(RenderPipelines.CLOUDS)
			assertSame(occlude, backend.pipeline)
			assertFalse(checkNotNull(occlude.depthStencilState).writeDepth, "borrowing scene depth must not write it")

			CloudVelocityRender.closeCloudVelocityPass(pass)
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
	 * The failure classes: payload-buffer allocation, payload write, MRT pass creation, and
	 * uniform bind. Each is preflighted or guarded before the pass is handed to the source
	 * render, so a failure can never leave the source render with a two-attachment pass its
	 * one-target pipeline cannot bind.
	 */
	@Test
	fun `eligible cloud pass setup preflights allocation write pass-creation and uniform-bind failures to the exact vanilla pass`() {
		val runtime = velocityRuntime()
		val phase = velocityWorldPhase(runtime)
		try {
			renderFrame(phase, mainTarget)
			phase.prepare(true, mainTarget, cameraSample())
			phase.begin(true, mainTarget)
			CloudVelocityRender.testPhaseOverride = phase
			CloudVelocityRender.currentCloudClock = { CloudVelocityRender.CloudClock(100L, 0.5f) }
			silenceFallbackLogger()

			val colorView = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM, RENDER_DIMENSIONS.width, RENDER_DIMENSIONS.height))
			val depthView = FakeView(FakeTexture(GpuFormat.D32_FLOAT, RENDER_DIMENSIONS.width, RENDER_DIMENSIONS.height))

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

				val pass = CloudVelocityRender.createCloudVelocityPass(
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
					CloudVelocityRender.bindCloudPipeline(pass, RenderPipelines.CLOUDS)
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
		val phase = velocityWorldPhase(runtime)
		try {
			renderFrame(phase, mainTarget)
			phase.prepare(true, mainTarget, cameraSample())
			phase.begin(true, mainTarget)
			CloudVelocityRender.testPhaseOverride = phase
			CloudVelocityRender.currentCloudClock = { CloudVelocityRender.CloudClock(100L, 0.5f) }
			silenceFallbackLogger()

			val colorView = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM, RENDER_DIMENSIONS.width, RENDER_DIMENSIONS.height))
			val depthView = FakeView(FakeTexture(GpuFormat.D32_FLOAT, RENDER_DIMENSIONS.width, RENDER_DIMENSIONS.height))

			for (case in listOf("precompile-throw", "precompile-invalid")) {
				CloudVelocityRender.resetState()
				val backend = CloudFakeBackend().also {
					it.failAt = if (case == "precompile-throw") "precompilePipeline" else null
					it.precompileInvalid = case == "precompile-invalid"
				}
				CloudVelocityRender.deviceProvider = { backend.device }

				assertDoesNotThrow {
					val pass = CloudVelocityRender.createCloudVelocityPass(
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
					CloudVelocityRender.bindCloudPipeline(pass, RenderPipelines.CLOUDS)
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
		val phase = velocityWorldPhase(runtime)
		try {
			renderFrame(phase, mainTarget)
			phase.prepare(true, mainTarget, cameraSample())
			phase.begin(true, mainTarget)
			CloudVelocityRender.testPhaseOverride = phase
			CloudVelocityRender.currentCloudClock = { CloudVelocityRender.CloudClock(100L, 0.5f) }

			val backend = CloudFakeBackend()
			CloudVelocityRender.deviceProvider = { backend.device }
			val colorView = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM, RENDER_DIMENSIONS.width, RENDER_DIMENSIONS.height))
			val mismatchedVelocity = FakeView(FakeTexture(GpuFormat.RG16_FLOAT, 640, 480))

			// The guard itself: the size-mismatched velocity view is rejected before the
			// encoder is touched, answering false without throwing.
			assertDoesNotThrow {
				assertFalse(
					CloudVelocityRender.preflightCloudPass(backend.encoder, phase, mismatchedVelocity, colorView, meshRebuilt = false),
					"a velocity view whose size differs from the color target is a preflight failure",
				)
			}
			assertTrue(backend.renderPassDescriptors.isEmpty(), "the rejected descriptor never reaches the encoder")

			// A matching view passes, so the mismatch alone - not the route - drives the fallback.
			assertDoesNotThrow {
				assertTrue(
					CloudVelocityRender.preflightCloudPass(backend.encoder, phase, checkNotNull(phase.terrainVelocityView), colorView, meshRebuilt = false),
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
		val phase = velocityWorldPhase(runtime)
		try {
			renderFrame(phase, mainTarget)
			phase.prepare(true, mainTarget, cameraSample())
			phase.begin(true, mainTarget)
			CloudVelocityRender.testPhaseOverride = phase
			CloudVelocityRender.currentCloudClock = { CloudVelocityRender.CloudClock(100L, 0.5f) }
			CloudVelocityRender.resetState()
			silenceFallbackLogger()

			val backend = CloudFakeBackend().also { it.failAt = "passClose" }
			CloudVelocityRender.deviceProvider = { backend.device }
			val colorView = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM, RENDER_DIMENSIONS.width, RENDER_DIMENSIONS.height))
			val depthView = checkNotNull(phase.sceneDepthView)

			val pass = CloudVelocityRender.createCloudVelocityPass(
				backend.encoder,
				{ "Clouds" },
				colorView,
				Optional.empty(),
				depthView,
				OptionalDouble.empty(),
				meshRebuilt = false,
			)
			CloudVelocityRender.bindCloudPipeline(pass, RenderPipelines.CLOUDS)

			// The close ran (the pass lifecycle completed) but its device-level failure was
			// absorbed: no throw, and the latch is gone.
			assertDoesNotThrow { CloudVelocityRender.closeCloudVelocityPass(pass) }
			assertEquals(1, backend.passCloses, "the pass closed exactly once, through the owned seam")
			assertFalse(CloudVelocityRender.isLatched(pass), "the close seam drops the latch even on a failed close")
			assertSame(writerTwin(RenderPipelines.CLOUDS, VelocityWriter.CLOUD), backend.pipeline)
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
		val phase = velocityWorldPhase(runtime)
		try {
			renderFrame(phase, mainTarget)
			phase.prepare(true, mainTarget, cameraSample())
			phase.begin(true, mainTarget)
			CloudVelocityRender.testPhaseOverride = phase
			CloudVelocityRender.currentCloudClock = { throw IllegalStateException("injected clock failure") }
			CloudVelocityRender.resetState()
			silenceFallbackLogger()

			val backend = CloudFakeBackend()
			CloudVelocityRender.deviceProvider = { backend.device }
			val colorView = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM, RENDER_DIMENSIONS.width, RENDER_DIMENSIONS.height))

			assertDoesNotThrow {
				val pass = CloudVelocityRender.createCloudVelocityPass(
					backend.encoder,
					{ "Clouds" },
					colorView,
					Optional.empty(),
					null,
					OptionalDouble.empty(),
					meshRebuilt = false,
				)
				assertEquals(1, checkNotNull(backend.renderPassDescriptors.lastOrNull()).colorAttachments().size)
				CloudVelocityRender.bindCloudPipeline(pass, RenderPipelines.CLOUDS)
				assertSame(RenderPipelines.CLOUDS, backend.pipeline, "the source pipeline binds unchanged")
			}
		} finally {
			resetSeams()
			if (phase.isOpen) phase.end()
		}
	}

	private fun resetSeams() {
		CloudVelocityRender.testPhaseOverride = null
		CloudVelocityRender.currentCloudClock = {
			// Mirrors production: the read is guarded, so a headless JVM with no client
			// degrades to a null clock instead of throwing.
			runCatching {
				net.minecraft.client.Minecraft.getInstance()
				CloudVelocityRender.CloudClock(0L, 0f)
			}.getOrNull()
		}
		CloudVelocityRender.deviceProvider = { RenderSystem.getDevice() }
		CloudVelocityRender.fallbackLogger = CloudVelocityRender.PRODUCTION_FALLBACK_LOGGER
	}

	private fun silenceFallbackLogger() {
		CloudVelocityRender.fallbackLogger = { _, _ -> }
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
		private val deviceBackend = CloudGpuDeviceBackend(deviceInfo)
		private val passBackend = CloudPassBackend(this)
		private val commandBackend = CloudCommandEncoderBackend()

		val encoder: CommandEncoder = CloudEncoder(this, deviceBackend, passBackend, commandBackend)
		val device: GpuDevice = CloudDevice(this, encoder, deviceBackend)

		fun failIf(point: String) {
			if (failAt == point) {
				error("injected $point failure")
			}
		}
	}

	/** The fake device: the payload buffer, and the precompile that stands in for lazy compile. */
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
			return CompiledRenderPipeline { !backend.precompileInvalid }
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
				error("injected createRenderPass failure")
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
	private class CloudGpuDeviceBackend(private val info: DeviceInfo) : GpuDeviceBackend {
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

	private companion object {
		private val OUTPUT_DIMENSIONS = Dimensions(2560, 1440)
		private val RENDER_DIMENSIONS = Dimensions(1707, 960)
		private val mainTarget = HeadlessRenderTarget(OUTPUT_DIMENSIONS.width, OUTPUT_DIMENSIONS.height)
	}
}
