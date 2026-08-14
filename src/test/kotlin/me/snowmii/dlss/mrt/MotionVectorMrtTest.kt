package me.snowmii.dlss.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.ShaderSource
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.CommandEncoderBackend
import com.mojang.blaze3d.systems.DeviceFeatures
import com.mojang.blaze3d.systems.DeviceInfo
import com.mojang.blaze3d.systems.DeviceLimits
import com.mojang.blaze3d.systems.DeviceType
import com.mojang.blaze3d.systems.GpuDeviceBackend
import com.mojang.blaze3d.systems.GpuQueryPool
import com.mojang.blaze3d.systems.GpuSurfaceBackend
import com.mojang.blaze3d.systems.HintsAndWorkarounds
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderPassBackend
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.systems.TransientMemory
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.OptionalDouble
import java.util.function.Supplier
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.bridge.DlssEvaluationImages
import me.snowmii.dlss.bridge.DlssFrameTimings
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.FillVelocityRequest
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.MotionRequest
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.PresentTarget
import me.snowmii.dlss.bridge.SrTagRequest
import me.snowmii.dlss.bridge.VulkanContext
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitter
import me.snowmii.dlss.render.DlssJitterOffset
import me.snowmii.dlss.render.FrameEvaluation
import me.snowmii.dlss.render.SceneResources
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.LifecycleAdapter
import net.minecraft.client.renderer.entity.state.EntityRenderState
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4fc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.vulkan.VkCommandBuffer

/**
 * The composed M-6 rung: one public-boundary suite proves entity, moving-block, and cloud
 * vectors survive the M-6.5 world while sentinel pixels receive camera motion.
 *
 * Each retained object writer is proven through its mod-owned public seam - the entity
 * render-state boundary and [EntityVelocityUniforms.writeFrame] payload, the
 * [MovingBlockVelocityRender] offset-delta reprojection and payload, and the
 * [CloudVelocityRender] drift state machine - and the M-6.5 fill is proven through the
 * [FrameEvaluation] VELOCITY_MRT boundary that hands the sentinel-merge its camera motion.
 * One wiring proof then drives all four seams through a single real world phase: the entity
 * and moving-block histories and the cloud clock advance on the same open VELOCITY_MRT phase
 * whose close hands the scene velocity companion and the exact camera motion the vectors were
 * composed against to the frame's evaluation (the fill's sentinel reconstruction input).
 *
 * The test JVM applies no Fabric transformation and owns no live device, so the payload
 * writes are recorded on a fake command encoder and the fill is recorded on a fake native ABI;
 * the live merged-pixel semantics of the fill are the M-6.5 rung's own evidence and are not
 * re-proven here.
 */
class MotionVectorMrtTest {
	private val mainTarget = fakeMainTarget()

	@Test
	fun `entity vectors survive the render-state boundary and the payload write`() {
		val runtime = velocityRuntime()
		val phase = worldPhase(runtime)
		val view = FakeView(FakeTexture(GpuFormat.RG16_FLOAT, RENDER_DIMENSIONS.width, RENDER_DIMENSIONS.height))
		try {
			// Frame one publishes the first observation: no predecessor for the next frame's read.
			val first = EntityRenderState()
			phase.captureEntity(first, 42, 10.0, 64.0, 5.0)
			renderFrame(phase, mainTarget)

			// Frame two: the entity moved half a block - the render-state boundary reads the
			// exact displacement, and the writer's payload carries the composed reprojection
			// with the valid classification instead of the sentinel.
			val second = EntityRenderState()
			phase.captureEntity(second, 42, 10.5, 64.0, 5.0)
			phase.prepare(true, mainTarget, cameraSample())
			phase.begin(true, mainTarget)
			assertTrue(phase.entityVelocityActive)
			assertEquals(42, phase.entityId(second), "the render-state boundary keeps the stable id")
			val displacement = checkNotNull(phase.objectMotionDisplacement(42))
			assertEquals(Vector3f(0.5f, 0f, 0f), displacement, "the entity's motion survives as its captured-minus-previous displacement")
			val expected = objectReprojection(
				checkNotNull(phase.activeMotion),
				checkNotNull(phase.currentViewProjection),
				checkNotNull(phase.activeJitter),
				displacement,
			)
			val encoder = payloadEncoder()
			EntityVelocityUniforms.writeFrame(encoder, PayloadFakeBuffer(), phase, 42, view)
			val payload = encoder.lastPayload()
			assertFalse(payload.invalid, "a predecessor frame writes the composed vector, never the sentinel")
			assertMatrixEquals(expected, payload.reprojection)
			assertEquals(RENDER_DIMENSIONS.width.toFloat(), payload.width, 1e-6f)
			assertEquals(RENDER_DIMENSIONS.height.toFloat(), payload.height, 1e-6f)

			// A first observation (no predecessor) writes the invalid classification: the
			// sentinel, not a fabricated vector.
			phase.captureEntity(43, 20.0, 64.0, 5.0)
			assertNull(phase.objectMotionDisplacement(43), "a first observation has no predecessor")
			EntityVelocityUniforms.writeFrame(encoder, PayloadFakeBuffer(), phase, 43, view)
			assertTrue(encoder.lastPayload().invalid, "a first observation must classify invalid in the payload")
		} finally {
			if (phase.isOpen) phase.end()
		}
	}

	@Test
	fun `moving block vectors survive through the offset-delta reprojection and payload write`() {
		val runtime = velocityRuntime()
		val phase = worldPhase(runtime)
		val id = MovingBlockVelocityWriterBindings.blockId(100, 64, -200)
		val view = FakeView(FakeTexture(GpuFormat.RG16_FLOAT, RENDER_DIMENSIONS.width, RENDER_DIMENSIONS.height))
		try {
			renderFrame(phase, mainTarget)
			phase.prepare(true, mainTarget, cameraSample())
			phase.begin(true, mainTarget)

			// First observation: the piston's first capture has no predecessor - the writer
			// classifies the invalid sentinel through both its public classification and the
			// payload write.
			phase.captureBlock(id, 100.0, 64.0, -200.0)
			assertNull(phase.blockMotionDisplacement(id), "a first observation has no predecessor")
			assertNull(
				MovingBlockVelocityRender.movingBlockReprojection(
					phase.activeMotion,
					phase.currentViewProjection,
					phase.activeJitter,
					null,
				),
				"a missing displacement classifies the sentinel",
			)
			val encoder = payloadEncoder()
			MovingBlockVelocityRender.writeFrame(encoder, PayloadFakeBuffer(), phase, id, view)
			assertTrue(encoder.lastPayload().invalid, "a first observation writes the sentinel classification")
			phase.end()

			// Second observation: the piston advanced a quarter block - the offset delta
			// survives as the displacement, the public reprojection is exactly the object
			// reprojection of that delta, and the payload carries it valid.
			phase.prepare(true, mainTarget, cameraSample())
			phase.begin(true, mainTarget)
			phase.captureBlock(id, 100.25, 64.0, -200.0)
			val delta = checkNotNull(phase.blockMotionDisplacement(id))
			assertEquals(0.25f, delta.x, 1e-6f, "the piston offset delta is the displacement")
			val expected = objectReprojection(
				checkNotNull(phase.activeMotion),
				checkNotNull(phase.currentViewProjection),
				checkNotNull(phase.activeJitter),
				delta,
			)
			assertMatrixEquals(
				expected,
				checkNotNull(
					MovingBlockVelocityRender.movingBlockReprojection(
						phase.activeMotion,
						phase.currentViewProjection,
						phase.activeJitter,
						delta,
					),
				),
				"the writer's reprojection is exactly the object reprojection with the offset delta",
			)
			MovingBlockVelocityRender.writeFrame(encoder, PayloadFakeBuffer(), phase, id, view)
			val payload = encoder.lastPayload()
			assertFalse(payload.invalid, "a predecessor frame writes the composed vector, never the sentinel")
			assertMatrixEquals(expected, payload.reprojection)
		} finally {
			if (phase.isOpen) phase.end()
		}
	}

	@Test
	fun `cloud vectors survive the M-6-5 merge with drift state and rebuild and discontinuity resets`() {
		var phase: WorldPhase? = null
		try {
			CloudVelocityRender.resetState()

			// The pure drift math: the delta is the game-clock advance, the displacement the
			// constant -0.03 blocks/tick toward -X.
			assertEquals(
				1.25f,
				CloudVelocityRender.driftDelta(
					CloudVelocityRender.CloudClock(100L, 0.5f),
					CloudVelocityRender.CloudClock(101L, 0.75f),
				),
				1e-6f,
			)
			val displacement = CloudVelocityRender.driftDisplacement(1.25f)
			assertEquals(-0.03f * 1.25f, displacement.x, 1e-6f)
			assertEquals(0f, displacement.y, 1e-6f)
			assertEquals(0f, displacement.z, 1e-6f)

			val runtime = velocityRuntime()
			phase = worldPhase(runtime)
			renderFrame(phase, mainTarget)
			phase.prepare(true, mainTarget, cameraSample())
			phase.begin(true, mainTarget)

			// First observation: no previous clock - the sentinel, and the state machine starts.
			var payload = CloudVelocityRender.cloudPayload(phase, gameTime = 100L, partialTicks = 0.5f, meshRebuilt = false)
			assertTrue(payload.invalid, "the first observation has no predecessor: the sentinel")

			// A continuous clock advance composes the drift-composed reprojection - exactly the
			// object reprojection of the 1.25-tick drift, surviving on the same open phase the
			// M-6.5 fill samples at close.
			payload = CloudVelocityRender.cloudPayload(phase, gameTime = 101L, partialTicks = 0.75f, meshRebuilt = false)
			assertFalse(payload.invalid, "a continuous clock advance composes the drift")
			assertMatrixEquals(
				objectReprojection(
					checkNotNull(phase.activeMotion),
					checkNotNull(phase.currentViewProjection),
					checkNotNull(phase.activeJitter),
					CloudVelocityRender.driftDisplacement(1.25f),
				),
				payload.reprojection,
				"the cloud vector survives as exactly the per-frame cloud-offset delta",
			)

			// A mesh rebuild resets only its own frame; the drift continues from its clock.
			payload = CloudVelocityRender.cloudPayload(phase, gameTime = 102L, partialTicks = 0.25f, meshRebuilt = true)
			assertTrue(payload.invalid, "a mesh rebuild writes the sentinel for that frame")
			payload = CloudVelocityRender.cloudPayload(phase, gameTime = 103L, partialTicks = 0.5f, meshRebuilt = false)
			assertFalse(payload.invalid, "the rebuild resets only the rebuild frame; the drift continues")

			// A clock discontinuity (a world change restarts the game clock) writes the
			// sentinel, and the state machine recovers from the new clock.
			payload = CloudVelocityRender.cloudPayload(phase, gameTime = 0L, partialTicks = 0f, meshRebuilt = false)
			assertTrue(payload.invalid, "a clock jump beyond the discontinuity bound is the sentinel")
			payload = CloudVelocityRender.cloudPayload(phase, gameTime = 1L, partialTicks = 0.5f, meshRebuilt = false)
			assertFalse(payload.invalid, "the state machine recovers from the discontinuity on the next frame")
		} finally {
			CloudVelocityRender.resetState()
			if (phase?.isOpen == true) phase.end()
		}
	}

	@Test
	fun `M-6-5 sentinel pixels receive camera motion through the velocity-route fill`() {
		val calls = RecordingNative(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)
		val velocity = ImageBinding(11L, 12L, 124)

		// The velocity-route frame fills the native motion image from the sampled companion
		// before tagging: the fill carries the scene depth, the companion binding, and the
		// frame's jitter-stripped camera reprojection - the motion every non-reset sentinel
		// pixel reconstructs - so no sentinel is ever left with a fabricated vector.
		assertTrue(
			evaluation.evaluateFrame(
				scene(),
				jitter(),
				motion(),
				route = MotionVectorRoute.VELOCITY_MRT,
				velocity = velocity,
			),
			"the velocity-route frame must record through to the evaluation",
		)
		val fill = calls.fills.single()
		assertEquals(velocity, fill.velocity, "the fill must sample the scene velocity companion")
		assertEquals(scene().depth, fill.depth, "the fill must read the scene depth image")
		val expectedReprojection = FloatArray(16).also { motion().reprojection.get(it) }
		assertTrue(
			expectedReprojection.contentEquals(fill.reprojection),
			"sentinel pixels must reconstruct from the frame's jitter-stripped camera reprojection",
		)
		assertFalse(fill.reset, "a continuous frame must not carry the reset flag")
		assertEquals(
			listOf("fill", "tag", "evaluate"),
			calls.order,
			"the fill must precede the tag in the frame's recording",
		)
		assertTrue(calls.writeMotion.isEmpty(), "the velocity route must not record the compute camera-motion writer")
		assertEquals(1, calls.evaluations.size, "the frame must still evaluate")

		// A reset frame has no valid predecessor: the fill carries the reset flag, so the
		// native merge blanks every pixel to the sentinel instead of reconstructing camera motion.
		val resetCalls = RecordingNative(RENDER_DIMENSIONS)
		val resetEvaluation = evaluation(resetCalls)
		assertTrue(
			resetEvaluation.evaluateFrame(
				scene(),
				jitter(),
				motion(reset = true),
				route = MotionVectorRoute.VELOCITY_MRT,
				velocity = velocity,
			),
		)
		assertTrue(resetCalls.fills.single().reset, "a reset frame must carry the reset flag to the fill")

		// The camera-only route keeps the compute writer and never fills.
		val cameraCalls = RecordingNative(RENDER_DIMENSIONS)
		val cameraEvaluation = evaluation(cameraCalls)
		assertTrue(cameraEvaluation.evaluateFrame(scene(), jitter(), motion()))
		assertTrue(cameraCalls.fills.isEmpty(), "the camera-only route must record no fill")
		assertEquals(1, cameraCalls.writeMotion.size)
	}

	@Test
	fun `one world frame wires entity block and cloud vectors into the open phase whose close hands the fill its camera motion`() {
		val runtime = velocityRuntime()
		val records = mutableListOf<ClosedFrame>()
		val phase = WorldPhase(
			runtime = runtime,
			present = { _, _ -> },
			onWorldTargetChanged = {},
			evaluateFrame = { _, _, jitter, motion, route, velocityView, _ ->
				records += ClosedFrame(route, velocityView, jitter, motion)
				true
			},
		)
		val state = EntityRenderState()
		val blockId = MovingBlockVelocityWriterBindings.blockId(100, 64, -200)
		try {
			CloudVelocityRender.resetState()

			// Frame one: an empty composed frame closes and hands the scene velocity companion
			// to the evaluation (the fill's input); the cloud clock takes its first observation.
			phase.prepare(true, mainTarget, cameraSample())
			phase.begin(true, mainTarget)
			assertTrue(CloudVelocityRender.cloudPayload(phase, gameTime = 100L, partialTicks = 0.5f, meshRebuilt = false).invalid)
			phase.end()
			assertEquals(MotionVectorRoute.VELOCITY_MRT, records.single().route, "every composed frame closes on the velocity route")
			assertNotNull(records.single().velocityView, "the composed frame hands the scene velocity companion to the fill")

			// Frame two: entity and moving block captured, published by the composed close.
			phase.captureEntity(state, 42, 10.0, 64.0, 5.0)
			phase.captureBlock(blockId, 100.0, 64.0, -200.0)
			renderFrame(phase, mainTarget)

			// Frame three: all three vectors survive on the same open phase - the entity's and
			// the moving block's displacements from the published frame, and the cloud's
			// drift-composed reprojection from the continuous clock - and the close hands the
			// fill the exact camera motion instance they were composed against.
			phase.prepare(true, mainTarget, cameraSample())
			phase.begin(true, mainTarget)
			phase.captureEntity(state, 42, 10.5, 64.0, 5.0)
			assertEquals(42, phase.entityId(state), "the render-state boundary keeps the stable id")
			phase.captureBlock(blockId, 100.25, 64.0, -200.0)
			assertEquals(Vector3f(0.5f, 0f, 0f), checkNotNull(phase.objectMotionDisplacement(42)))
			assertEquals(0.25f, checkNotNull(phase.blockMotionDisplacement(blockId)).x, 1e-6f)
			val cloud = CloudVelocityRender.cloudPayload(phase, gameTime = 101L, partialTicks = 0.75f, meshRebuilt = false)
			assertFalse(cloud.invalid, "the cloud vector survives on the open phase")
			assertMatrixEquals(
				objectReprojection(
					checkNotNull(phase.activeMotion),
					checkNotNull(phase.currentViewProjection),
					checkNotNull(phase.activeJitter),
					CloudVelocityRender.driftDisplacement(1.25f),
				),
				cloud.reprojection,
			)
			val jitterAtClose = phase.activeJitter
			val motionAtClose = phase.activeMotion
			phase.end()
			val frame = records.last()
			assertEquals(MotionVectorRoute.VELOCITY_MRT, frame.route)
			assertNotNull(frame.velocityView, "the fill receives the scene velocity companion")
			assertSame(jitterAtClose, frame.jitter, "the fill receives the jitter the vectors were composed against")
			assertSame(motionAtClose, frame.motion, "the fill receives the exact camera motion the vectors were composed against")

			// Frame four: the composed close published the predecessors - the entity and block
			// vectors chain on to the next frame.
			phase.prepare(true, mainTarget, cameraSample())
			phase.begin(true, mainTarget)
			phase.captureEntity(state, 42, 11.0, 64.0, 5.0)
			phase.captureBlock(blockId, 100.5, 64.0, -200.0)
			assertEquals(Vector3f(0.5f, 0f, 0f), checkNotNull(phase.objectMotionDisplacement(42)), "the entity history published at the composed close")
			assertEquals(0.25f, checkNotNull(phase.blockMotionDisplacement(blockId)).x, 1e-6f, "the block history published at the composed close")
		} finally {
			CloudVelocityRender.resetState()
			if (phase.isOpen) phase.end()
		}
	}

	/** One closed frame's evaluation handoff, as the production wiring drives it. */
	private data class ClosedFrame(
		val route: MotionVectorRoute,
		val velocityView: GpuTextureView?,
		val jitter: DlssJitterOffset?,
		val motion: DlssFrameMotion?,
	)

	/** One recorded payload write: the std140 `mat4 ObjectReprojection` + `vec4 VelocityParams`. */
	private class Payload(
		val reprojection: Matrix4f,
		val invalid: Boolean,
		val width: Float,
		val height: Float,
	)

	/** Decodes the writer payloads' std140 block: 64 bytes of column-major matrix, then the vec4. */
	private fun PayloadRecordingEncoder.lastPayload(): Payload {
		val bytes = checkNotNull(backend.writes.lastOrNull()) { "no payload was written" }
		assertEquals(EntityVelocityUniforms.UBO_SIZE, bytes.remaining(), "the payload is exactly the mat4 + vec4 block")
		val floats = bytes.asFloatBuffer()
		val matrix = Matrix4f()
		for (column in 0 until 4) {
			for (row in 0 until 4) {
				matrix.set(column, row, floats.get(column * 4 + row))
			}
		}
		return Payload(matrix, floats.get(16) != 0f, floats.get(17), floats.get(18))
	}

	private fun payloadEncoder() = PayloadRecordingEncoder(PayloadBackend())

	/** The payload buffer the writers write through: the same usage and size they own in production. */
	private class PayloadFakeBuffer : GpuBuffer(GpuBuffer.USAGE_COPY_DST, EntityVelocityUniforms.UBO_SIZE.toLong()) {
		override fun isClosed() = false
		override fun close() = Unit
		override fun map(offset: Long, length: Long, read: Boolean, write: Boolean): GpuBufferSlice.MappedView =
			throw UnsupportedOperationException("payload tests never map buffers")
	}

	private fun assertMatrixEquals(expected: Matrix4f, actual: Matrix4f, message: String = "the reprojection matrix") {
		for (column in 0 until 4) {
			for (row in 0 until 4) {
				assertEquals(expected.get(column, row), actual.get(column, row), 1e-4f, "$message ($column,$row)")
			}
		}
	}

	/** The recording encoder the payload writes run on: records each std140 block verbatim. */
	private class PayloadRecordingEncoder(val backend: PayloadBackend) :
		CommandEncoder(null, PayloadGpuDeviceBackend(), backend) {

		override fun createRenderPass(descriptor: RenderPassDescriptor): RenderPass =
			throw UnsupportedOperationException("payload tests never create passes")
	}

	/** Records the payload write; every other encoder call is unreachable in a payload write. */
	private class PayloadBackend : CommandEncoderBackend {
		val writes = mutableListOf<ByteBuffer>()

		override fun submit() = Unit

		override fun transientMemory(): TransientMemory = throw UnsupportedOperationException("payload tests never allocate transient memory")

		override fun createRenderPass(descriptor: RenderPassDescriptor): RenderPassBackend =
			throw UnsupportedOperationException("payload tests never create passes")

		override fun submitRenderPass() = Unit

		override fun writeToBuffer(destination: GpuBufferSlice, data: ByteBuffer) {
			// The payload buffer is a little-endian stack allocation; copy bytes and order while
			// the call is live so the recorded block decodes exactly as written.
			val copy = ByteBuffer.allocate(data.remaining()).order(data.order())
			copy.put(data.duplicate()).flip()
			writes += copy
		}

		override fun clearColorTexture(colorTexture: GpuTexture, clearColor: Vector4fc) = Unit

		override fun clearColorAndDepthTextures(colorTexture: GpuTexture, clearColor: Vector4fc, depthTexture: GpuTexture, clearDepth: Double) = Unit

		override fun clearColorAndDepthTextures(colorTexture: GpuTexture, clearColor: Vector4fc, depthTexture: GpuTexture, clearDepth: Double, regionX: Int, regionY: Int, regionWidth: Int, regionHeight: Int) = Unit

		override fun clearDepthTexture(depthTexture: GpuTexture, clearDepth: Double) = Unit

		override fun copyToBuffer(source: GpuBufferSlice, target: GpuBufferSlice) = Unit

		override fun writeToTexture(destination: GpuTexture, source: ByteBuffer, mipLevel: Int, depthOrLayer: Int, destX: Int, destY: Int, width: Int, height: Int) = Unit

		override fun copyBufferToTexture(source: GpuBufferSlice, sourceX: Int, sourceY: Int, sourceWidth: Int, sourceHeight: Int, destination: GpuTexture, destinationX: Int, destinationY: Int, copyWidth: Int, copyHeight: Int, mipLevel: Int, arrayLayer: Int) = Unit

		override fun copyTextureToBuffer(source: GpuTexture, destination: GpuBuffer, offset: Long, callback: Runnable, mipLevel: Int) = Unit

		override fun copyTextureToBuffer(source: GpuTexture, destination: GpuBuffer, offset: Long, callback: Runnable, mipLevel: Int, x: Int, y: Int, width: Int, height: Int) = Unit

		override fun copyTextureToTexture(source: GpuTexture, destination: GpuTexture, mipLevel: Int, destX: Int, destY: Int, sourceX: Int, sourceY: Int, width: Int, height: Int) = Unit

		override fun createFence() = throw UnsupportedOperationException("payload tests never create fences")

		override fun writeTimestamp(pool: GpuQueryPool, index: Int) = Unit
	}

	/** The device-info side of the fake device the encoder constructor requires; never driven. */
	private class PayloadGpuDeviceBackend : GpuDeviceBackend {
		private val info = DeviceInfo(
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

		override fun createSurface(windowHandle: Long): GpuSurfaceBackend = throw UnsupportedOperationException("payload tests never create surfaces")
		override fun createCommandEncoder(): CommandEncoderBackend = throw UnsupportedOperationException("payload tests never create encoders")
		override fun createSampler(addressModeU: AddressMode, addressModeV: AddressMode, minFilter: FilterMode, magFilter: FilterMode, maxAnisotropy: Int, maxLod: OptionalDouble): GpuSampler =
			throw UnsupportedOperationException("payload tests never create samplers")
		override fun createTexture(label: Supplier<String>?, usage: Int, format: GpuFormat, width: Int, height: Int, depthOrLayers: Int, mipLevels: Int): GpuTexture =
			throw UnsupportedOperationException("payload tests never create textures")
		override fun createTexture(label: String?, usage: Int, format: GpuFormat, width: Int, height: Int, depthOrLayers: Int, mipLevels: Int): GpuTexture =
			throw UnsupportedOperationException("payload tests never create textures")
		override fun createTextureView(texture: GpuTexture): GpuTextureView = throw UnsupportedOperationException("payload tests never create texture views")
		override fun createTextureView(texture: GpuTexture, baseMipLevel: Int, mipLevels: Int): GpuTextureView =
			throw UnsupportedOperationException("payload tests never create texture views")
		override fun createBuffer(label: Supplier<String>?, usage: Int, size: Long): GpuBuffer = throw UnsupportedOperationException("payload tests never allocate buffers")
		override fun createBuffer(label: Supplier<String>?, usage: Int, data: ByteBuffer): GpuBuffer = throw UnsupportedOperationException("payload tests never allocate buffers")
		override fun getLastDebugMessages(): List<String> = emptyList()
		override fun isDebuggingEnabled(): Boolean = false
		override fun precompilePipeline(pipeline: RenderPipeline, shaderSource: ShaderSource?): com.mojang.blaze3d.pipeline.CompiledRenderPipeline =
			throw UnsupportedOperationException("payload tests never compile pipelines")
		override fun clearPipelineCache() = Unit
		override fun close() = Unit
		override fun createTimestampQueryPool(size: Int): GpuQueryPool = throw UnsupportedOperationException("payload tests never create query pools")
		override fun getTimestampNow(): Long = 0L
		override fun getDeviceInfo(): DeviceInfo = info
	}

	private fun evaluation(calls: RecordingNative): FrameEvaluation {
		val session = DlssSession(startupConfig())
		val adapter = LifecycleAdapter(session, calls)
		adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data"))
		val context = VulkanContext.fromNativeHandles(
			1L,
			2L,
			3L,
			4L,
			commandBufferSource = { fakeCommandBuffer() },
			commandBufferSink = {},
		)
		return FrameEvaluation(adapter, { context })
	}

	/** A [VkCommandBuffer] instance whose address() answers without any Vulkan device. */
	private fun fakeCommandBuffer(): VkCommandBuffer {
		val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
		unsafeField.isAccessible = true
		val unsafe = unsafeField.get(null) as sun.misc.Unsafe
		return unsafe.allocateInstance(VkCommandBuffer::class.java) as VkCommandBuffer
	}

	private fun scene() = SceneResources(
		color = ImageBinding(201L, 202L, 37),
		depth = ImageBinding(301L, 302L, 126),
	)

	private fun jitter(): DlssJitterOffset = DlssJitter(RENDER_DIMENSIONS, OUTPUT_DIMENSIONS).advance()

	private fun motion(reset: Boolean = false) =
		DlssFrameMotion(Matrix4f(), RENDER_DIMENSIONS.width / 2f, RENDER_DIMENSIONS.height / 2f, 16.6f, reset)

	/** Records every per-frame native call so the fill boundary is assertable off the render thread. */
	private class RecordingNative(
		private val RENDER_DIMENSIONS: DlssDimensions,
	) : NativeApi {
		val fills = mutableListOf<FillVelocityRequest>()
		val writeMotion = mutableListOf<MotionRequest>()
		val tags = mutableListOf<SrTagRequest>()
		val evaluations = mutableListOf<EvaluationRequest>()
		/** The per-frame recording calls in submission order, so the fill-before-tag seam is assertable. */
		val order = mutableListOf<String>()

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = NativeApi.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int): DlssDimensions =
			DlssDimensions(RENDER_DIMENSIONS.width, RENDER_DIMENSIONS.height)

		override fun configure(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = NativeApi.SUCCESS_RESULT

		override fun acquireImages(): DlssEvaluationImages = DlssEvaluationImages(
			motion = ImageBinding(401L, 402L, 124),
			output = ImageBinding(501L, 502L, 37),
		)

		override fun releaseImages(): Int = NativeApi.SUCCESS_RESULT

		override fun waitDeviceIdle(): Int = NativeApi.SUCCESS_RESULT

		override fun frameTimings(): DlssFrameTimings? = null

		override fun fillVelocity(request: FillVelocityRequest): Int {
			fills += request
			order += "fill"
			return NativeApi.SUCCESS_RESULT
		}

		override fun writeMotion(request: MotionRequest): Int {
			writeMotion += request
			order += "writeMotion"
			return NativeApi.SUCCESS_RESULT
		}

		override fun tagSrResources(request: SrTagRequest): Int {
			tags += request
			order += "tag"
			return NativeApi.SUCCESS_RESULT
		}

		override fun presentOutput(target: PresentTarget): Int {
			order += "present"
			return NativeApi.SUCCESS_RESULT
		}

		override fun evaluate(request: EvaluationRequest): Int {
			evaluations += request
			order += "evaluate"
			return NativeApi.SUCCESS_RESULT
		}
	}

	private companion object {
		val RENDER_DIMENSIONS = DlssDimensions(1280, 720)
		val OUTPUT_DIMENSIONS = DlssDimensions(2560, 1440)
	}
}
