package me.snowmii.dlss.mrt

import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.systems.ScissorState
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.PreparedRenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.resources.Identifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Entity-model vertical proof for M-6's first dynamic writer.
 *
 * Descriptor tests exercise every supported core/entity pipeline family. Behavior tests drive the
 * CPU identity/batch state and safe no-identity draw fallback directly; reflection checks bind the
 * mapped 26.2 methods, callback descriptors, and JSON registration. The shader itself is compiled
 * and its output locations verified by `VelocityWriterContractTest`, which owns that proof for
 * every writer. The test JVM does not apply Fabric mixins or own a live Blaze3D RenderPass, so
 * this suite makes no live transformed/GPU draw claim.
 */
class EntityMotionVectorTest {
	private val mainTarget = fakeMainTarget()

	@Test
	fun `returned render state keeps stable id and displacement through active velocity phase`() {
		val runtime = velocityRuntime(withVelocity = true)
		val phase = worldPhase(runtime)
		val first = EntityRenderState()
		phase.captureEntity(first, 42, 10.0, 64.0, 5.0)
		renderFrame(phase, mainTarget)

		val second = EntityRenderState()
		phase.captureEntity(second, 42, 10.5, 64.0, 5.0)
		phase.prepare(true, mainTarget, cameraSample())
		phase.begin(true, mainTarget)
		assertTrue(phase.entityVelocityActive)
		assertEquals(42, phase.entityId(second))
		assertEquals(org.joml.Vector3f(0.5f, 0f, 0f), runtime.objectMotion.displacement(42))
		assertNotNull(phase.activeJitter)
		assertNotNull(phase.currentViewProjection)
		phase.end()
		assertFalse(phase.isOpen)
	}

	@Test
	fun `entity identity is not assigned by position and missing state falls back to sentinel`() {
		val runtime = velocityRuntime(withVelocity = true)
		val phase = worldPhase(runtime)
		val state = EntityRenderState()
		phase.captureEntity(state, 7, 10.0, 64.0, 5.0)
		renderFrame(phase, mainTarget)

		phase.prepare(true, mainTarget, cameraSample())
		phase.begin(true, mainTarget)
		assertEquals(null, phase.entityId(EntityRenderState()), "unpaired state cannot borrow another entity id")
		assertEquals(null, runtime.objectMotion.displacement(7), "first predecessor is invalid")
		phase.end()
	}

	@Test
	fun `prepared draw control falls through safely without an entity identity`() {
		EntityVelocityWriterBindings.clearFrame()
		val prepared = PreparedRenderType(
			RenderPipelines.ENTITY_SOLID,
			OutputTarget.MAIN_TARGET,
			FakeBuffer().slice(),
			ScissorState(),
			emptyList(),
		)
		assertFalse(
			EntityVelocityRender.draw(prepared, emptyExecuteInfo()),
			"without a bound ExecuteInfo identity callback must not cancel vanilla draw",
		)
	}

	@Test
	fun `draw control accepts a mapped entity identity before any GPU operation`() {
		val runtime = velocityRuntime(withVelocity = true)
		val phase = worldPhase(runtime)
		phase.prepare(true, mainTarget, cameraSample())
		phase.begin(true, mainTarget)
		val staged = StagedVertexBuffer({ "entity-control-test" }, 256)
		try {
			EntityVelocityWriterBindings.clearFrame()
			EntityVelocityWriterBindings.beginEntity(303)
			assertTrue(EntityVelocityWriterBindings.beginDraw(RenderPipelines.ENTITY_SOLID, true))
			val draw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val info = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(draw)
			EntityVelocityWriterBindings.bindExecuteInfo(draw, info)

			val prepared = PreparedRenderType(
				RenderPipelines.ENTITY_SOLID,
				OutputTarget.MAIN_TARGET,
				FakeBuffer().slice(),
				ScissorState(),
				emptyList(),
			)
			assertTrue(EntityVelocityRender.canDraw(prepared, info, phase))
		} finally {
			EntityVelocityWriterBindings.clearFrame()
			staged.close()
			if (phase.isOpen) phase.end()
		}
	}

	@Test
	fun `non-main entity render type stays vanilla while main target binds identity`() {
		val texture = Identifier.fromNamespaceAndPath("minecraft", "textures/entity/entity-boundary.png")
		val mainRenderType = RenderTypes.entitySolid(texture)
		val nonMainRenderType = RenderTypes.entityTranslucentCullItemTarget(texture)
		assertSame(OutputTarget.MAIN_TARGET, mainRenderType.outputTarget())
		assertSame(OutputTarget.ITEM_ENTITY_TARGET, nonMainRenderType.outputTarget())
		assertTrue(EntityVelocityWriterBindings.isEligibleRenderType(mainRenderType, true))
		assertFalse(EntityVelocityWriterBindings.isEligibleRenderType(nonMainRenderType, true))
		val renderTypes = mutableListOf<Any>()
		val prepared = Any()
		val staged = StagedVertexBuffer({ "entity-output-target-test" }, 256)
		try {
			EntityVelocityWriterBindings.clearFrame()
			EntityVelocityWriterBindings.beginEntity(404)
			val mainEligible = EntityVelocityWriterBindings.isEligibleRenderType(mainRenderType, true)
			assertTrue(EntityVelocityWriterBindings.beginDraw(mainRenderType.pipeline(), mainEligible))
			val mainDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val mainInfo = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(mainDraw)
			EntityVelocityWriterBindings.bindExecuteInfo(mainDraw, mainInfo)
			assertEquals(404, EntityVelocityWriterBindings.executeInfoEntityId(mainInfo))
			EntityVelocityWriterBindings.endDraw()
			EntityVelocityWriterBindings.endEntity()

			EntityVelocityWriterBindings.clearFrame()
			EntityVelocityWriterBindings.beginEntity(505)
			val nonMainEligible = EntityVelocityWriterBindings.isEligibleRenderType(nonMainRenderType, true)
			assertFalse(EntityVelocityWriterBindings.beginDraw(nonMainRenderType.pipeline(), nonMainEligible))
			assertFalse(EntityVelocityWriterBindings.suppressConsolidation())
			renderTypes += prepared
			assertEquals(0, EntityVelocityWriterBindings.consolidationIndex(renderTypes, prepared))
			val nonMainDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val nonMainInfo = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(nonMainDraw)
			EntityVelocityWriterBindings.bindExecuteInfo(nonMainDraw, nonMainInfo)
			assertEquals(null, EntityVelocityWriterBindings.executeInfoEntityId(nonMainInfo))
		} finally {
			EntityVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	@Test
	fun `entity then no-id same render type gets fresh draw and next entity keeps own id`() {
		val renderType = RenderTypes.entitySolid(
			Identifier.fromNamespaceAndPath("minecraft", "textures/entity/entity-boundary.png"),
		)
		val pipeline = renderType.pipeline()
		val preparedType = PreparedRenderType(
			pipeline,
			OutputTarget.MAIN_TARGET,
			FakeBuffer().slice(),
			ScissorState(),
			emptyList(),
		)
		val drawRenderTypes = mutableListOf<Any>()
		val staged = StagedVertexBuffer({ "entity-boundary-test" }, 256)
		try {
			EntityVelocityWriterBindings.clearFrame()

			EntityVelocityWriterBindings.beginEntity(101)
			assertTrue(EntityVelocityWriterBindings.beginDraw(pipeline, true))
			assertEquals(-1, EntityVelocityWriterBindings.consolidationIndex(drawRenderTypes, preparedType))
			drawRenderTypes += preparedType
			val firstDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val firstInfo = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(firstDraw)
			EntityVelocityWriterBindings.bindExecuteInfo(firstDraw, firstInfo)
			assertEquals(101, EntityVelocityWriterBindings.executeInfoEntityId(firstInfo))
			EntityVelocityWriterBindings.endDraw()
			EntityVelocityWriterBindings.endEntity()

			// A block/entity-model submit has no dispatcher identity. It uses the same RenderType,
			// so this call must clear both Group.lastDraw and the reorder lookup before staging.
			EntityVelocityWriterBindings.beginEntity(null)
			assertTrue(EntityVelocityWriterBindings.beginDraw(pipeline, false))
			assertTrue(EntityVelocityWriterBindings.suppressConsolidation(), "transition draw must defeat reorder reuse")
			assertEquals(-1, EntityVelocityWriterBindings.consolidationIndex(drawRenderTypes, preparedType))
			drawRenderTypes += preparedType
			val blockDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val blockInfo = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(blockDraw)
			EntityVelocityWriterBindings.bindExecuteInfo(blockDraw, blockInfo)
			assertEquals(null, EntityVelocityWriterBindings.executeInfoEntityId(blockInfo), "block geometry must not inherit entity 101")
			EntityVelocityWriterBindings.endDraw()
			EntityVelocityWriterBindings.endEntity()

			// After the transition, unrelated no-id geometry gets vanilla consolidation again.
			assertFalse(EntityVelocityWriterBindings.beginDraw(pipeline, false))
			assertFalse(EntityVelocityWriterBindings.suppressConsolidation())
			assertEquals(1, EntityVelocityWriterBindings.consolidationIndex(drawRenderTypes, preparedType))
			EntityVelocityWriterBindings.endDraw()

			EntityVelocityWriterBindings.beginEntity(202)
			assertTrue(EntityVelocityWriterBindings.beginDraw(pipeline, true))
			assertEquals(-1, EntityVelocityWriterBindings.consolidationIndex(drawRenderTypes, preparedType))
			val secondDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val secondInfo = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(secondDraw)
			EntityVelocityWriterBindings.bindExecuteInfo(secondDraw, secondInfo)
			assertEquals(202, EntityVelocityWriterBindings.executeInfoEntityId(secondInfo))
			assertNotEquals(firstDraw, secondDraw, "each eligible entity starts a distinct staged draw")
		} finally {
			EntityVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	@Test
	fun `ineligible route keeps vanilla reorder consolidation when no entity preceded it`() {
		EntityVelocityWriterBindings.clearFrame()
		val renderTypes = mutableListOf<Any>()
		val prepared = Any()
		assertFalse(EntityVelocityWriterBindings.beginDraw(RenderPipelines.ITEM_CUTOUT, false))
		renderTypes += prepared
		EntityVelocityWriterBindings.endDraw()
		assertFalse(EntityVelocityWriterBindings.beginDraw(RenderPipelines.ITEM_CUTOUT, false))
		assertEquals(0, EntityVelocityWriterBindings.consolidationIndex(renderTypes, prepared))
		EntityVelocityWriterBindings.endDraw()
		EntityVelocityWriterBindings.clearFrame()
	}

	@Test
	fun `only core entity shader family is eligible and camera-only keeps source path`() {
		assertTrue(EntityVelocityUniforms.isSupportedPipeline(RenderPipelines.ENTITY_SOLID))
		assertTrue(EntityVelocityUniforms.isSupportedPipeline(RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE))
		assertFalse(EntityVelocityUniforms.isSupportedPipeline(RenderPipelines.ITEM_CUTOUT))
		assertFalse(EntityVelocityUniforms.isSupportedPipeline(RenderPipelines.SOLID_TERRAIN))
	}

	private fun emptyExecuteInfo() = StagedVertexBuffer.ExecuteInfo(
		FakeBuffer(),
		FakeBuffer(),
		IndexType.INT,
		0,
		0,
		3,
	)
}
