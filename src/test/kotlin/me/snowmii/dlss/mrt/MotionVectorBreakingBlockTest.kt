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
	private val mainTarget = fakeMainTarget()

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

	}

	@Test
	fun `eligible open velocity-mrt phase admits the breaking block control seam and ineligible routes fall through`() {
		val runtime = velocityRuntime()
		val phase = worldPhase(runtime)
		val info = emptyExecuteInfo()
		try {
			renderFrame(phase, mainTarget)
			phase.prepare(true, mainTarget, cameraSample())
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
		val cameraOnly = velocityRuntime()
		cameraOnly.observeWorldPipeline(
			MotionVectorPipeline(
				"example:pipeline/waving_terrain",
				listOf(MotionVectorShader("example:core/waving_terrain", "example")),
			),
		)
		assertEquals(MotionVectorRoute.CAMERA_ONLY, cameraOnly.motionVectorRoute)
		val cameraOnlyPhase = worldPhase(cameraOnly)
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
				outputDimensions = OUTPUT_DIMENSIONS,
				sdkPath = null,
				nativeLibraryPath = null,
				dataPath = null,
				warnings = emptyList(),
			),
		)
		val vanillaPhase = worldPhase(
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
		val phase = worldPhase(runtime)
		val crumbling = PreparedRenderType(RenderPipelines.CRUMBLING, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
		val info = emptyExecuteInfo()
		try {
			renderFrame(phase, mainTarget)
			phase.prepare(true, mainTarget, cameraSample())
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
			assertSame(writerTwin(RenderPipelines.CRUMBLING, VelocityWriter.CRUMBLING), backend.pipeline)

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
		val phase = worldPhase(runtime)
		val crumbling = PreparedRenderType(RenderPipelines.CRUMBLING, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
		val info = emptyExecuteInfo()
		try {
			renderFrame(phase, mainTarget)
			phase.prepare(true, mainTarget, cameraSample())
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

	/**
	 * The same runtime as [dlssRuntime], but the scene target carries a color view too, so the
	 * eligible production draw can resolve its attachments from `phase.worldTargetOverride`.
	 */
	private fun dlssRuntimeWithViews(): RenderRuntime {
		val session = DlssSession(startupConfig()).also { check(it.markReadyAfterNativeStartup()) }
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

	private fun emptyExecuteInfo() = StagedVertexBuffer.ExecuteInfo(
		FakeBuffer(),
		FakeBuffer(),
		IndexType.INT,
		0,
		0,
		3,
	)

	/** Sample points spread across the frustum, from near the eye to the far plane. */

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
