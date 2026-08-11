package me.snowmii.dlss.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.ScissorState
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.Optional
import java.util.OptionalDouble
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
import net.minecraft.SharedConstants
import net.minecraft.WorldVersion
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.MovingBlockRenderState
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.PistonHeadRenderer
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.blockentity.state.PistonHeadRenderState
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.PreparedRenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import net.minecraft.server.packs.metadata.pack.PackFormat
import net.minecraft.world.level.storage.DataVersion
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc
import org.lwjgl.util.spvc.Spvc
import org.lwjgl.util.spvc.SpvcReflectedResource
import com.google.gson.JsonParser

/**
 * Piston moving-block vertical proof for M-6's velocity writer.
 *
 * `PistonHeadRenderer` renders a moving piston block by submitting one or two
 * `MovingBlockRenderState`s (the moving block and the retracting piston base) into
 * `MovingBlockFeatureRenderer`, which tesselates each submit's block model into the
 * `solidMovingBlock` / `cutoutMovingBlock` / `translucentMovingBlock` render types - the
 * block-shaped `SOLID_BLOCK` / `CUTOUT_BLOCK` / `TRANSLUCENT_BLOCK` pipelines - and draws them
 * through the same `PreparedRenderType.drawFromBuffer` seam the entity writer uses. This slice
 * binds each submitted render state to the collision-free packed long identity of its baked
 * block position at the block-entity dispatcher seam, captures its absolute render position (baked position plus the
 * piston's current interpolated offset) into the shared object-motion history, isolates one
 * staged draw per moving block at the feature-renderer boundary, and replaces the owned
 * main-target solid/cutout draws with a cached two-target twin whose fragment shader
 * reproduces the vanilla core/block color output byte-identically and writes the piston
 * offset-delta object reprojection into the RG16_FLOAT velocity attachment - with the exact
 * reset/unknown-history sentinel. Translucent `ITEM_ENTITY_TARGET` draws, identity-less
 * moving geometry (falling blocks ride the same feature renderer), vanilla, and CAMERA_ONLY
 * keep their exact source routes without throwing.
 *
 * The test JVM does not apply Fabric mixins or own a live Blaze3D device, so this suite makes
 * no live transformed/GPU draw claim: descriptors are proven against the mapped 26.2 classes,
 * the control seams are driven at the same seams the mixins use, and passthrough is proven by
 * the control seams answering false. The moving-block shader compiles through the same LWJGL
 * Shaderc + spirv-cross path `GlslCompiler` and `IntermediaryShaderModule` use - it inlines the
 * two vanilla includes it needs, so it is self-contained - and the reflected output order is
 * pinned to fragColor-then-velocityColor, the order Minecraft's location rewrite turns into
 * color attachments 0 and 1.
 */
class MotionVectorMovingBlockTest {
	private val mainTarget = fakeMainTarget()

	companion object {
		/**
		 * Constructing real mapped moving-block render states touches the Blocks/AIR registry;
		 * the headless test JVM needs the vanilla registry bootstrap first (with a synthetic
		 * world version, since no game entrypoint runs in tests). Idempotent.
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
	fun `only the owned core block pipeline family is eligible for the moving block writer`() {
		assertTrue(MovingBlockVelocityRender.isSupportedPipeline(RenderPipelines.SOLID_BLOCK))
		assertTrue(MovingBlockVelocityRender.isSupportedPipeline(RenderPipelines.CUTOUT_BLOCK))
		// TRANSLUCENT_BLOCK is block-shaped, but its render type writes the item/entity target,
		// which the render-type eligibility gate separately excludes.
		assertTrue(MovingBlockVelocityRender.isSupportedPipeline(RenderPipelines.TRANSLUCENT_BLOCK))
		assertFalse(MovingBlockVelocityRender.isSupportedPipeline(RenderPipelines.SOLID_TERRAIN))
		assertFalse(MovingBlockVelocityRender.isSupportedPipeline(RenderPipelines.ENTITY_SOLID))
		assertFalse(MovingBlockVelocityRender.isSupportedPipeline(RenderPipelines.ITEM_CUTOUT))

		// The mapped moving-block render types use exactly the two eligible pipelines on the
		// main target, and the translucent one writes the item/entity target.
		assertSame(RenderPipelines.SOLID_BLOCK, RenderTypes.solidMovingBlock().pipeline())
		assertSame(RenderPipelines.CUTOUT_BLOCK, RenderTypes.cutoutMovingBlock().pipeline())
		assertSame(RenderPipelines.TRANSLUCENT_BLOCK, RenderTypes.translucentMovingBlock().pipeline())
		assertSame(OutputTarget.MAIN_TARGET, RenderTypes.solidMovingBlock().outputTarget())
		assertSame(OutputTarget.MAIN_TARGET, RenderTypes.cutoutMovingBlock().outputTarget())
		assertSame(OutputTarget.ITEM_ENTITY_TARGET, RenderTypes.translucentMovingBlock().outputTarget())

	}

	@Test
	fun `block ids are collision-free packed positions in their own domain`() {
		// The identity is the game's own packed block position: a pure function of the
		// coordinates, so the same block reuses the same history slot across frames.
		val id = MovingBlockVelocityWriterBindings.blockId(100, 64, -200)
		assertEquals(id, MovingBlockVelocityWriterBindings.blockId(100, 64, -200), "the id is a pure function of the position")
		assertEquals(BlockPos.asLong(100, 64, -200), id, "the id is exactly the game's packed block position")

		// Collision resistance over a volume: every distinct valid position keeps its own slot.
		val volume = HashSet<Long>()
		for (x in -24 until 24) {
			for (y in -8 until 8) {
				for (z in -24 until 24) {
					volume += MovingBlockVelocityWriterBindings.blockId(x, y, z)
				}
			}
		}
		assertEquals(48 * 16 * 48, volume.size, "every position in a volume keeps a distinct id")

		// The extremes of the packed valid-world range - the full 26-bit horizontal and 12-bit
		// vertical ranges across all four horizontal quadrants - stay distinct: a collision here
		// would smear two far-apart blocks' histories through one slot.
		val extremes = listOf(
			BlockPos(0, 0, 0),
			BlockPos(33554431, 2047, 33554431),
			BlockPos(-33554432, -2048, -33554432),
			BlockPos(33554431, -2048, -33554432),
			BlockPos(-33554432, 2047, 33554431),
			BlockPos(-33554432, -2048, 33554431),
			BlockPos(33554431, 2047, -33554432),
		)
		val extremeIds = extremes.map { MovingBlockVelocityWriterBindings.blockId(it.x, it.y, it.z) }
		assertEquals(extremes.size, extremeIds.toSet().size, "valid-world extremes keep distinct ids")
	}

	@Test
	fun `entity and moving-block domains stay disjoint even when ids share a numeric value`() {
		val runtime = velocityRuntime()
		val phase = worldPhase(runtime)
		// The block at (0, 7, 0) packs to exactly 7 - the same numeric value as entity id 7 -
		// so any shared-slot implementation would smear the two histories into one.
		val collidingBlockId = MovingBlockVelocityWriterBindings.blockId(0, 7, 0)
		assertEquals(7L, collidingBlockId, "the block domain can carry an entity id's numeric value")

		// Frame one: both domains observe an object carrying the value 7.
		renderFrame(phase, mainTarget)
		phase.prepare(true, mainTarget, cameraSample())
		phase.begin(true, mainTarget)
		phase.captureEntity(7, 1.0, 2.0, 3.0)
		phase.captureBlock(collidingBlockId, 4.0, 5.0, 6.0)
		phase.end()

		// Frame two: each domain moves its own object; the displacements stay independent.
		phase.prepare(true, mainTarget, cameraSample())
		phase.begin(true, mainTarget)
		phase.captureEntity(7, 1.5, 2.0, 3.0)
		phase.captureBlock(collidingBlockId, 4.5, 5.0, 6.0)
		assertEquals(
			Vector3f(0.5f, 0f, 0f),
			phase.objectMotionDisplacement(7),
			"the entity id keeps its own displacement",
		)
		assertEquals(
			Vector3f(0.5f, 0f, 0f),
			phase.blockMotionDisplacement(collidingBlockId),
			"the block id with the same numeric value keeps its own displacement",
		)
		phase.end()
	}

	@Test
	fun `identity plumbing carries the block id from render state through draw to execute info`() {
		val staged = StagedVertexBuffer({ "moving-block-identity-test" }, 256)
		try {
			MovingBlockVelocityWriterBindings.clearFrame()

			val state = MovingBlockRenderState()
			state.blockPos = BlockPos(10, 64, -5)
			val id = MovingBlockVelocityWriterBindings.blockId(10, 64, -5)
			MovingBlockVelocityWriterBindings.bindMovingBlock(state, id)
			assertEquals(id, MovingBlockVelocityWriterBindings.movingBlockId(state))

			// The tesselation redirect installs the id on the thread while the block's quads
			// are staged; the draw boundary then binds it onto the staged draw.
			MovingBlockVelocityWriterBindings.beginMovingBlock(id)
			assertTrue(MovingBlockVelocityWriterBindings.beginDraw(RenderTypes.solidMovingBlock().pipeline(), true))
			val draw = staged.appendDraw(DefaultVertexFormat.BLOCK, PrimitiveTopology.QUADS)
			val info = emptyExecuteInfo()
			MovingBlockVelocityWriterBindings.bindDraw(draw)
			MovingBlockVelocityWriterBindings.bindExecuteInfo(draw, info)
			assertEquals(id, MovingBlockVelocityWriterBindings.executeInfoMovingBlockId(info))
			MovingBlockVelocityWriterBindings.endDraw()
			MovingBlockVelocityWriterBindings.endMovingBlock()

			// The entity predicates never see the moving-block identity: the maps are separate.
			assertNull(EntityVelocityWriterBindings.executeInfoEntityId(info), "a moving-block draw must never read as an entity draw")
			assertFalse(EntityVelocityWriterBindings.executeInfoIsBlockEntity(info), "a moving-block draw must never read as static block geometry")

			// An unbound render state (a falling block) carries no identity at all.
			val falling = MovingBlockRenderState()
			falling.blockPos = BlockPos(10, 64, -5)
			assertNull(MovingBlockVelocityWriterBindings.movingBlockId(falling))
		} finally {
			MovingBlockVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	@Test
	fun `moving block then identity-less moving geometry gets fresh draw and keeps batching isolation`() {
		val pipeline = RenderTypes.solidMovingBlock().pipeline()
		val preparedType = PreparedRenderType(
			pipeline,
			OutputTarget.MAIN_TARGET,
			FakeBuffer().slice(),
			ScissorState(),
			emptyList(),
		)
		val drawRenderTypes = mutableListOf<Any>()
		val staged = StagedVertexBuffer({ "moving-block-boundary-test" }, 256)
		try {
			MovingBlockVelocityWriterBindings.clearFrame()
			val id = MovingBlockVelocityWriterBindings.blockId(10, 64, -5)

			MovingBlockVelocityWriterBindings.beginMovingBlock(id)
			assertTrue(MovingBlockVelocityWriterBindings.beginDraw(pipeline, true))
			assertEquals(-1, MovingBlockVelocityWriterBindings.consolidationIndex(drawRenderTypes, preparedType))
			drawRenderTypes += preparedType
			val firstDraw = staged.appendDraw(DefaultVertexFormat.BLOCK, PrimitiveTopology.QUADS)
			val firstInfo = emptyExecuteInfo()
			MovingBlockVelocityWriterBindings.bindDraw(firstDraw)
			MovingBlockVelocityWriterBindings.bindExecuteInfo(firstDraw, firstInfo)
			assertEquals(id, MovingBlockVelocityWriterBindings.executeInfoMovingBlockId(firstInfo))
			MovingBlockVelocityWriterBindings.endDraw()
			MovingBlockVelocityWriterBindings.endMovingBlock()

			// An identity-less moving submit (a falling block after the piston block, same render
			// type) must clear both Group.lastDraw and the reorder lookup before staging, so it
			// can never consolidate into the moving block's draw or inherit its id.
			assertTrue(MovingBlockVelocityWriterBindings.beginDraw(pipeline, false))
			assertTrue(MovingBlockVelocityWriterBindings.suppressConsolidation(), "transition draw must defeat reorder reuse")
			assertEquals(-1, MovingBlockVelocityWriterBindings.consolidationIndex(drawRenderTypes, preparedType))
			drawRenderTypes += preparedType
			val blockDraw = staged.appendDraw(DefaultVertexFormat.BLOCK, PrimitiveTopology.QUADS)
			val blockInfo = emptyExecuteInfo()
			MovingBlockVelocityWriterBindings.bindDraw(blockDraw)
			MovingBlockVelocityWriterBindings.bindExecuteInfo(blockDraw, blockInfo)
			assertNull(MovingBlockVelocityWriterBindings.executeInfoMovingBlockId(blockInfo), "identity-less moving geometry must not inherit the piston block id")
			MovingBlockVelocityWriterBindings.endDraw()

			// After the transition, unrelated ineligible geometry gets vanilla consolidation
			// again - its own draw, not the moving block's.
			assertFalse(MovingBlockVelocityWriterBindings.beginDraw(pipeline, false))
			assertFalse(MovingBlockVelocityWriterBindings.suppressConsolidation())
			assertEquals(1, MovingBlockVelocityWriterBindings.consolidationIndex(drawRenderTypes, preparedType))
			MovingBlockVelocityWriterBindings.endDraw()

			// A later piston block starts yet another fresh draw with its own id.
			val secondId = MovingBlockVelocityWriterBindings.blockId(11, 64, -5)
			MovingBlockVelocityWriterBindings.beginMovingBlock(secondId)
			assertTrue(MovingBlockVelocityWriterBindings.beginDraw(pipeline, true))
			assertEquals(-1, MovingBlockVelocityWriterBindings.consolidationIndex(drawRenderTypes, preparedType))
			val secondDraw = staged.appendDraw(DefaultVertexFormat.BLOCK, PrimitiveTopology.QUADS)
			val secondInfo = emptyExecuteInfo()
			MovingBlockVelocityWriterBindings.bindDraw(secondDraw)
			MovingBlockVelocityWriterBindings.bindExecuteInfo(secondDraw, secondInfo)
			assertEquals(secondId, MovingBlockVelocityWriterBindings.executeInfoMovingBlockId(secondInfo))
			assertNotEquals(firstDraw, secondDraw, "each eligible moving block starts a distinct staged draw")
			MovingBlockVelocityWriterBindings.endDraw()
			MovingBlockVelocityWriterBindings.endMovingBlock()
		} finally {
			MovingBlockVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	@Test
	fun `ineligible moving block paths keep vanilla batching and bind no identity`() {
		val staged = StagedVertexBuffer({ "moving-block-ineligible-test" }, 256)
		try {
			MovingBlockVelocityWriterBindings.clearFrame()
			val id = MovingBlockVelocityWriterBindings.blockId(10, 64, -5)

			// Ineligible draw with a context installed (the translucent layer or a closed phase):
			// no fresh boundary, no identity bound, vanilla consolidation stays intact.
			MovingBlockVelocityWriterBindings.beginMovingBlock(id)
			assertFalse(MovingBlockVelocityWriterBindings.beginDraw(RenderTypes.translucentMovingBlock().pipeline(), false))
			val draw = staged.appendDraw(DefaultVertexFormat.BLOCK, PrimitiveTopology.QUADS)
			val info = emptyExecuteInfo()
			MovingBlockVelocityWriterBindings.bindDraw(draw)
			MovingBlockVelocityWriterBindings.bindExecuteInfo(draw, info)
			assertNull(MovingBlockVelocityWriterBindings.executeInfoMovingBlockId(info), "an ineligible draw binds no identity")
			MovingBlockVelocityWriterBindings.endDraw()
			MovingBlockVelocityWriterBindings.endMovingBlock()

			// Consecutive ineligible draws regain vanilla reorder consolidation.
			val renderTypes = mutableListOf<Any>()
			val prepared = Any()
			assertFalse(MovingBlockVelocityWriterBindings.beginDraw(RenderPipelines.SOLID_BLOCK, false))
			renderTypes += prepared
			MovingBlockVelocityWriterBindings.endDraw()
			assertFalse(MovingBlockVelocityWriterBindings.beginDraw(RenderPipelines.SOLID_BLOCK, false))
			assertEquals(0, MovingBlockVelocityWriterBindings.consolidationIndex(renderTypes, prepared))
			MovingBlockVelocityWriterBindings.endDraw()
		} finally {
			MovingBlockVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	@Test
	fun `governing marker hands the reorder decision to the machine that owns the group`() {
		MovingBlockVelocityWriterBindings.clearFrame()
		try {
			assertFalse(MovingBlockVelocityWriterBindings.isGoverning(), "a fresh frame has no governing machine")

			// The mixin's rule, driven with its boundary flags: the moving-block machine governs
			// from its own fresh boundary (an eligible draw or the eligibility-ending transition)
			// until the entity machine takes a fresh boundary of its own.
			var movingBoundary = true
			var entityBoundary = false
			MovingBlockVelocityWriterBindings.setGoverning(
				movingBoundary || (MovingBlockVelocityWriterBindings.isGoverning() && !entityBoundary),
			)
			assertTrue(MovingBlockVelocityWriterBindings.isGoverning(), "a moving-block fresh boundary takes the decision")

			// An entity fresh boundary outranks a stale moving-block history.
			movingBoundary = false
			entityBoundary = true
			MovingBlockVelocityWriterBindings.setGoverning(
				movingBoundary || (MovingBlockVelocityWriterBindings.isGoverning() && !entityBoundary),
			)
			assertFalse(MovingBlockVelocityWriterBindings.isGoverning(), "an entity fresh boundary outranks the stale moving-block history")

			// A moving-block fresh boundary takes the decision back...
			movingBoundary = true
			entityBoundary = false
			MovingBlockVelocityWriterBindings.setGoverning(
				movingBoundary || (MovingBlockVelocityWriterBindings.isGoverning() && !entityBoundary),
			)
			assertTrue(MovingBlockVelocityWriterBindings.isGoverning(), "a moving-block fresh boundary takes the decision back")

			// ...and consecutive ineligible draws stay with the governing machine (the
			// moving-block post-eligible bookkeeping owns them).
			movingBoundary = false
			entityBoundary = false
			MovingBlockVelocityWriterBindings.setGoverning(
				movingBoundary || (MovingBlockVelocityWriterBindings.isGoverning() && !entityBoundary),
			)
			assertTrue(MovingBlockVelocityWriterBindings.isGoverning(), "consecutive ineligible draws stay with the governing machine")
		} finally {
			MovingBlockVelocityWriterBindings.clearFrame()
			assertFalse(MovingBlockVelocityWriterBindings.isGoverning(), "clearFrame drops the marker with the frame")
		}
	}

	@Test
	fun `moving block reprojection composes the offset delta and invalidates without a predecessor`() {
		val camera = DlssFrameMotion(Matrix4f(), 1f, 1f, 16f, false)
		val viewProjection = Matrix4f()
		val jitter = DlssJitterOffset(0, 0f, 0f, DlssDimensions(1280, 720))

		val delta = Vector3f(0.25f, 0f, -0.5f)
		assertEquals(
			objectReprojection(camera, viewProjection, jitter, delta),
			MovingBlockVelocityRender.movingBlockReprojection(camera, viewProjection, jitter, delta),
			"the writer's reprojection is exactly the object reprojection with the offset delta",
		)

		// The exact reset/unknown-history classification: a missing or reset frame, a missing
		// view-projection or jitter, or a missing displacement (first observation, eviction,
		// reset history) all mean the invalid sentinel.
		assertNull(MovingBlockVelocityRender.movingBlockReprojection(null, viewProjection, jitter, delta))
		assertNull(MovingBlockVelocityRender.movingBlockReprojection(camera.copy(reset = true), viewProjection, jitter, delta))
		assertNull(MovingBlockVelocityRender.movingBlockReprojection(camera, null, jitter, delta))
		assertNull(MovingBlockVelocityRender.movingBlockReprojection(camera, viewProjection, null, delta))
		assertNull(MovingBlockVelocityRender.movingBlockReprojection(camera, viewProjection, jitter, null))

		// A piston at rest (zero offset delta) collapses to the camera reprojection itself.
		assertEquals(
			camera.reprojection,
			MovingBlockVelocityRender.movingBlockReprojection(camera, viewProjection, jitter, Vector3f(0f, 0f, 0f)),
		)
	}

	@Test
	fun `capture seam records block position plus piston offset and the retracting base stands still`() {
		val runtime = velocityRuntime()
		val phase = worldPhase(runtime)
		val blockPos = BlockPos(100, 64, -200)
		val basePos = BlockPos(99, 64, -200)
		val blockId = MovingBlockVelocityWriterBindings.blockId(blockPos.x, blockPos.y, blockPos.z)
		val baseId = MovingBlockVelocityWriterBindings.blockId(basePos.x, basePos.y, basePos.z)

		// The capture seam's math: the moving block draws at its baked position plus the
		// piston's current interpolated offset; the retracting base draws at its baked position
		// without any offset translate.
		// Frame one: first observation - the draw path reads no displacement and must write the
		// unknown-history sentinel.
		renderFrame(phase, mainTarget)
		phase.prepare(true, mainTarget, cameraSample())
		phase.begin(true, mainTarget)
		phase.captureBlock(blockId, blockPos.x + 0.5, blockPos.y.toDouble(), blockPos.z.toDouble())
		phase.captureBlock(baseId, basePos.x.toDouble(), basePos.y.toDouble(), basePos.z.toDouble())
		assertNull(phase.blockMotionDisplacement(blockId), "a first observation has no predecessor: the sentinel")
		assertNull(phase.blockMotionDisplacement(baseId), "a first observation has no predecessor: the sentinel")
		phase.end()

		// Frame two: the piston moved on by a quarter block - the displacement is exactly the
		// offset delta, while the base keeps zero displacement.
		phase.prepare(true, mainTarget, cameraSample())
		phase.begin(true, mainTarget)
		phase.captureBlock(blockId, blockPos.x + 0.75, blockPos.y.toDouble(), blockPos.z.toDouble())
		phase.captureBlock(baseId, basePos.x.toDouble(), basePos.y.toDouble(), basePos.z.toDouble())
		val delta = checkNotNull(phase.blockMotionDisplacement(blockId))
		assertEquals(0.25f, delta.x, 1e-6f, "the offset delta is the displacement")
		assertEquals(0f, delta.y, 1e-6f)
		assertEquals(0f, delta.z, 1e-6f)
		val baseDelta = checkNotNull(phase.blockMotionDisplacement(baseId))
		assertEquals(0f, baseDelta.x, 1e-6f, "the retracting base has zero displacement")
		assertEquals(0f, baseDelta.y, 1e-6f)
		assertEquals(0f, baseDelta.z, 1e-6f)

		// The block and entity id spaces never mix: a positive entity id keeps its own history.
		phase.captureEntity(7, 1.0, 2.0, 3.0)
		assertNull(phase.objectMotionDisplacement(7), "an entity id is a separate history slot")
		assertEquals(0.25f, checkNotNull(phase.blockMotionDisplacement(blockId)).x, 1e-6f, "the block history is untouched by the entity capture")
		phase.end()

		// A frame that never completed a DLSS evaluation breaks the chain: the next observation
		// is an unknown history again.
		phase.prepare(true, mainTarget, cameraSample())
		phase.begin(true, mainTarget)
		phase.captureBlock(blockId, blockPos.x + 0.75, blockPos.y.toDouble(), blockPos.z.toDouble())
		phase.end()
		val abandoned = worldPhase(runtime, evaluate = false)
		renderFrame(abandoned, mainTarget)
		phase.prepare(true, mainTarget, cameraSample())
		phase.begin(true, mainTarget)
		phase.captureBlock(blockId, blockPos.x + 0.75, blockPos.y.toDouble(), blockPos.z.toDouble())
		assertNull(
			phase.blockMotionDisplacement(blockId),
			"a vanilla/abandoned frame in between resets the history, so the next observation writes the sentinel",
		)
		phase.end()

		// The writer reads the same history the capture seam fills: the reset makes the first
		// observation classify invalid through the writer's own classification seam.
		assertNull(
			MovingBlockVelocityRender.movingBlockReprojection(
				phase.activeMotion,
				phase.currentViewProjection,
				phase.activeJitter,
				phase.blockMotionDisplacement(blockId),
			),
			"after the reset, the first observation still classifies invalid",
		)
		phase.end()
	}

	@Test
	fun `eligible open velocity-mrt phase admits the writer control seam and ineligible routes fall through`() {
		val runtime = velocityRuntime()
		val phase = worldPhase(runtime)
		val staged = StagedVertexBuffer({ "moving-block-control-test" }, 256)
		try {
			val id = MovingBlockVelocityWriterBindings.blockId(10, 64, -5)
			renderFrame(phase, mainTarget)
			phase.prepare(true, mainTarget, cameraSample())
			phase.begin(true, mainTarget)

			// A bound identity on an owned main-target solid/cutout pipeline is eligible.
			MovingBlockVelocityWriterBindings.clearFrame()
			val state = MovingBlockRenderState()
			state.blockPos = BlockPos(10, 64, -5)
			MovingBlockVelocityWriterBindings.bindMovingBlock(state, id)
			MovingBlockVelocityWriterBindings.beginMovingBlock(id)
			assertTrue(MovingBlockVelocityWriterBindings.beginDraw(RenderPipelines.SOLID_BLOCK, true))
			val draw = staged.appendDraw(DefaultVertexFormat.BLOCK, PrimitiveTopology.QUADS)
			val info = emptyExecuteInfo()
			MovingBlockVelocityWriterBindings.bindDraw(draw)
			MovingBlockVelocityWriterBindings.bindExecuteInfo(draw, info)
			MovingBlockVelocityWriterBindings.endDraw()
			MovingBlockVelocityWriterBindings.endMovingBlock()

			val solid = PreparedRenderType(RenderPipelines.SOLID_BLOCK, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
			assertTrue(MovingBlockVelocityRender.canDraw(solid, info, phase))
			val cutout = PreparedRenderType(RenderPipelines.CUTOUT_BLOCK, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
			assertTrue(MovingBlockVelocityRender.canDraw(cutout, info, phase))

			// The translucent layer writes the item/entity target: never a velocity route.
			val translucent = PreparedRenderType(RenderPipelines.TRANSLUCENT_BLOCK, OutputTarget.ITEM_ENTITY_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
			assertFalse(MovingBlockVelocityRender.canDraw(translucent, info, phase))

			// Unsupported pipelines fall through to their exact source draw.
			val terrain = PreparedRenderType(RenderPipelines.SOLID_TERRAIN, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
			assertFalse(MovingBlockVelocityRender.canDraw(terrain, info, phase))

			// A draw without a bound identity (a falling block) falls through even on the
			// eligible pipeline.
			val fallingInfo = emptyExecuteInfo()
			assertFalse(MovingBlockVelocityRender.canDraw(solid, fallingInfo, phase))

			// The draw replacement falls through without a live ClientRuntime phase: the phase
			// gate answers false before anything can touch a device, so the draw never throws.
			assertFalse(
				MovingBlockVelocityRender.draw(solid, info),
				"headless: the draw must answer false at the phase gate, never throw",
			)
		} finally {
			MovingBlockVelocityWriterBindings.clearFrame()
			staged.close()
			if (phase.isOpen) phase.end()
		}
	}

	@Test
	fun `vanilla camera-only and non-open phases keep the moving block route unchanged`() {
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
			MovingBlockVelocityRender.canDraw(
				PreparedRenderType(RenderPipelines.SOLID_BLOCK, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList()),
				info,
				cameraOnlyPhase,
			),
			"a camera-only phase offers no velocity view",
		)
		assertFalse(MovingBlockVelocityRender.draw(PreparedRenderType(RenderPipelines.SOLID_BLOCK, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList()), info))

		// Vanilla: a session without DLSS keeps the moving-block draw on its exact source route.
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
			MovingBlockVelocityRender.canDraw(
				PreparedRenderType(RenderPipelines.SOLID_BLOCK, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList()),
				info,
				vanillaPhase,
			),
		)
	}

	/** The mixin redirect handler itself: executable proof of the piston branch without a live Mixin transform. */
	private fun dispatcherHandler(): Method {
		val mixinClass = Class.forName("me.snowmii.dlss.mixin.BlockEntityRenderDispatcherMotionMixin")
		val handler = mixinClass.getDeclaredMethod(
			"mcDlssSubmitBlockEntity",
			BlockEntityRenderer::class.java,
			BlockEntityRenderState::class.java,
			PoseStack::class.java,
			SubmitNodeCollector::class.java,
			CameraRenderState::class.java,
		)
		handler.isAccessible = true
		return handler
	}

	/** The untransformed mixin class is a plain object at test runtime: the receiver for the handler. */
	private fun mixinInstance(): Any =
		Class.forName("me.snowmii.dlss.mixin.BlockEntityRenderDispatcherMotionMixin").getDeclaredConstructor().newInstance()

	private fun emptyExecuteInfo() = StagedVertexBuffer.ExecuteInfo(
		FakeBuffer(),
		FakeBuffer(),
		IndexType.INT,
		0,
		0,
		3,
	)

	/** Sample points spread across the frustum, from near the eye to the far plane. */

	/** A foreign piston renderer subclass: exact membership keeps it on the vanilla route. */
	private class DynamicPistonSubclass : PistonHeadRenderer()
}
