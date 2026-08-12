package me.snowmii.dlss.mrt

import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.systems.ScissorState
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import me.snowmii.dlss.render.DlssFrameMotion
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.feature.CustomFeatureRenderer
import net.minecraft.client.renderer.feature.TextFeatureRenderer
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.PreparedRenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hand/item velocity vertical proof for M-6's hand writer.
 *
 * The hand writer owns three things the shared writer contracts do not prove: the per-slot
 * per-domain pose history and its reset classification ([HandMotionState], including the
 * render-selection epoch), the identity plumbing that carries the hand slot and pose domain from
 * the submission bracket through batching to the draw ([HandVelocityWriterBindings]), and the
 * three hand geometry families - item, player arm, and map/custom geometry - that
 * `submitArmWithItem` renders. The twin shape is the shared [writerTwin] seam plus the one
 * policy deviation the hand owns - always-pass, depth-write-off - so this suite proves that
 * deviation against the full twins and leaves the shared descriptor contract to
 * [VelocityWriterContractTest].
 *
 * The classification math is proven directly: a first observation, an item-identity change, a
 * render-selection change, a disappeared hand, a failed pose capture, and a broken camera chain
 * all write the invalid sentinel, while a continuous hand pair composes `C_prev * inverse(C_cur)`
 * - the exact camera-attached reprojection, with no jitter terms. The slot state is driven
 * through the same entry points the mixins call; the prepared-draw replacement is exercised
 * through its control seam, which answers false at the phase gate without a live device exactly
 * like the other writers.
 */
class MotionVectorHandTest {
	private val texture = Identifier.fromNamespaceAndPath("minecraft", "textures/item/diamond_sword.png")

	private fun motion(reset: Boolean = false) = DlssFrameMotion(
		reprojection = Matrix4f().rotateX(0.05f),
		motionScaleX = 800f,
		motionScaleY = 450f,
		frameTimeMillis = 16f,
		reset = reset,
	)

	@Test
	fun `the first observation writes the sentinel`() {
		val state = HandMotionState()
		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", Matrix4f())
		// Nothing committed yet: no predecessor exists to reproject against.
		assertNull(state.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, motion()))
	}

	@Test
	fun `a valid hand pair composes C_prev times inverse C_cur and a still pose is the identity`() {
		val state = HandMotionState()
		val previous = Matrix4f().rotateX(0.1f).translate(0f, -0.2f, -0.5f)
		val current = Matrix4f().rotateX(0.4f).translate(0.1f, -0.1f, -0.4f)

		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", previous)
		state.commit()
		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", current)

		val expected = Matrix4f(previous).mul(Matrix4f(current).invert())
		assertTrue(expected.equals(state.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, motion())!!, 1e-4f))

		// A still pose is a valid zero-motion reprojection, not a sentinel: a hand that did not
		// move is camera-attached, so its pixels stay put and the identity maps clip to itself.
		val still = HandMotionState()
		still.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", current)
		still.commit()
		still.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", current)
		assertTrue(Matrix4f().equals(still.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, motion())!!, 1e-6f))
	}

	@Test
	fun `an item identity change writes the sentinel`() {
		val state = HandMotionState()
		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", Matrix4f().translate(0f, 0f, -0.5f))
		state.commit()
		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "pickaxe", Matrix4f().translate(0f, 0f, -0.5f))
		assertNull(state.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, motion()))

		// The same item instance across frames stays valid: the swap animation keeps the pose
		// continuous, and only the renderer's own visible-item identity change resets.
		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", Matrix4f().translate(0f, 0f, -0.5f))
		assertTrue(state.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, motion()) != null)
	}

	@Test
	fun `a disappeared hand writes the sentinel on its return`() {
		val state = HandMotionState()
		state.capture(HandSlot.OFF_HAND, HandPoseDomain.Item, "torch", Matrix4f().translate(0.4f, 0f, -0.5f))
		state.commit()
		// A frame without the hand (spectator, scoping, a render selection that dropped it):
		// the slot commits no predecessor.
		state.commit()
		state.capture(HandSlot.OFF_HAND, HandPoseDomain.Item, "torch", Matrix4f().translate(0.4f, 0f, -0.5f))
		assertNull(state.reprojection(HandSlot.OFF_HAND, HandPoseDomain.Item, motion()))
	}

	@Test
	fun `a reset or missing camera chain writes the sentinel`() {
		val state = HandMotionState()
		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", Matrix4f().translate(0f, 0f, -0.5f))
		state.commit()
		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", Matrix4f().translate(0f, 0f, -0.5f))
		assertNull(state.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, motion(reset = true)))
		assertNull(state.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, null))
	}

	@Test
	fun `a failed pose capture writes the sentinel`() {
		val state = HandMotionState()
		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", Matrix4f().translate(0f, 0f, -0.5f))
		state.commit()
		// The capture seam could not read a matrix (a throw in the read, a missing frame
		// projection): the observation is recorded but its clip is null.
		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", null)
		assertNull(state.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, motion()))
	}

	@Test
	fun `main and off hand slots are independent`() {
		val state = HandMotionState()
		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", Matrix4f().translate(0f, 0f, -0.5f))
		state.commit()
		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", Matrix4f().translate(0f, 0f, -0.5f))

		// The off hand was never observed: its slot answers the sentinel while the main hand's
		// is valid, and a first off-hand observation stays a first observation on the next frame.
		assertTrue(state.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, motion()) != null)
		assertNull(state.reprojection(HandSlot.OFF_HAND, HandPoseDomain.Item, motion()))
		state.commit()
		state.capture(HandSlot.OFF_HAND, HandPoseDomain.Item, "torch", Matrix4f().translate(0.4f, 0f, -0.5f))
		assertNull(state.reprojection(HandSlot.OFF_HAND, HandPoseDomain.Item, motion()))
		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", Matrix4f().translate(0f, 0f, -0.5f))
		assertTrue(
			state.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, motion()) != null,
			"the off hand's history cannot disturb the main hand's",
		)
	}

	@Test
	fun `item arm and map pose domains stay independent within one slot`() {
		val state = HandMotionState()
		val rightArm = Any()
		val itemPose = Matrix4f().rotateX(0.1f).translate(0f, -0.2f, -0.5f)
		val armPose = Matrix4f().rotateX(0.3f).translate(0.1f, 0.1f, -0.4f)
		val mapPose = Matrix4f().rotateX(0.05f).translate(0.3f, -0.1f, -0.6f)

		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", itemPose)
		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Arm(rightArm), rightArm, armPose)
		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Map, "map", mapPose)
		state.noteSelection(setOf(HandSlot.MAIN_HAND))
		state.commit()

		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", Matrix4f(itemPose).rotateZ(0.2f))
		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Arm(rightArm), rightArm, Matrix4f(armPose).rotateZ(0.4f))
		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Map, "map", Matrix4f(mapPose).rotateZ(0.1f))
		state.noteSelection(setOf(HandSlot.MAIN_HAND))

		// Each domain composes its own pose pair: the arm's motion never reads the item's or
		// the map's predecessor and vice versa.
		assertTrue(
			Matrix4f(itemPose).mul(Matrix4f(Matrix4f(itemPose).rotateZ(0.2f)).invert())
				.equals(state.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, motion())!!, 1e-4f),
		)
		assertTrue(
			Matrix4f(armPose).mul(Matrix4f(Matrix4f(armPose).rotateZ(0.4f)).invert())
				.equals(state.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Arm(rightArm), motion())!!, 1e-4f),
		)
		assertTrue(
			Matrix4f(mapPose).mul(Matrix4f(Matrix4f(mapPose).rotateZ(0.1f)).invert())
				.equals(state.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Map, motion())!!, 1e-4f),
		)

		// A different arm part is a first observation for its own lineage.
		assertNull(state.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Arm(Any()), motion()))
		// A map identity change resets the map domain without disturbing the item domain.
		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Map, "otherMap", Matrix4f(mapPose).rotateZ(0.1f))
		assertNull(state.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Map, motion()))
		assertTrue(state.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, motion()) != null)
	}

	@Test
	fun `the frame selection is the set of bracketed hands and a change resets an unchanged item`() {
		HandVelocityWriterBindings.clearFrame()
		try {
			HandVelocityWriterBindings.beginHand(HandSlot.MAIN_HAND)
			HandVelocityWriterBindings.beginHand(HandSlot.OFF_HAND)
			assertEquals(
				setOf(HandSlot.MAIN_HAND, HandSlot.OFF_HAND),
				HandVelocityWriterBindings.frameHandSlots(),
			)
			HandVelocityWriterBindings.endHand()
			HandVelocityWriterBindings.endHand()
			assertEquals(
				setOf(HandSlot.MAIN_HAND, HandSlot.OFF_HAND),
				HandVelocityWriterBindings.frameHandSlots(),
			)
		} finally {
			HandVelocityWriterBindings.clearFrame()
		}

		val state = HandMotionState()
		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", Matrix4f().translate(0f, 0f, -0.5f))
		state.noteSelection(setOf(HandSlot.MAIN_HAND, HandSlot.OFF_HAND))
		state.commit()
		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", Matrix4f().translate(0f, 0f, -0.5f))

		// Unchanged selection: the item instance is unchanged and the history is continuous.
		state.noteSelection(setOf(HandSlot.MAIN_HAND, HandSlot.OFF_HAND))
		assertTrue(state.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, motion()) != null)

		// Selection change (a bow starts charging): the same item instance, but the surviving
		// hand's pose chain jumps, so the frame resets.
		state.noteSelection(setOf(HandSlot.MAIN_HAND))
		assertNull(state.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, motion()))
		state.commit()

		// The new selection is the baseline: the next continuous frame is valid again.
		state.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", Matrix4f().translate(0f, 0f, -0.5f))
		state.noteSelection(setOf(HandSlot.MAIN_HAND))
		assertTrue(state.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, motion()) != null)
	}

	@Test
	fun `a selection change invalidates the frame at draw time before the frame boundary`() {
		// The production lifecycle through the mod's own seams: the bracket capture, the
		// per-bracket selection note, the draw-time read, and the frame boundary. The draws
		// execute after the last bracket closes but before the boundary, and the selection
		// change must already invalidate the frame when the draw reads the reprojection.
		val projection = Matrix4f().setPerspective(1.2f, 16f / 9f, 100f, 0.05f, true)
		val modelView = Matrix4f().rotateY(0.3f)
		val pose = Matrix4f().rotateX(0.2f).translate(0f, -0.2f, -0.5f)
		HandVelocityRender.resetState()
		HandVelocityWriterBindings.clearFrame()
		try {
			// Frame 1: both hands render. The first observation has no predecessor, so the draw
			// classifies the sentinel even though the selection becomes the new baseline.
			HandVelocityRender.beginHandFrame(projection)
			HandVelocityWriterBindings.beginHand(HandSlot.MAIN_HAND)
			HandVelocityRender.captureHandPose(HandSlot.MAIN_HAND, "sword", HandVelocityRender.frameProjection(), modelView, pose)
			HandVelocityWriterBindings.endHand()
			HandVelocityRender.noteHandSelection()
			HandVelocityWriterBindings.beginHand(HandSlot.OFF_HAND)
			HandVelocityRender.captureHandPose(HandSlot.OFF_HAND, "torch", HandVelocityRender.frameProjection(), modelView, pose)
			HandVelocityWriterBindings.endHand()
			HandVelocityRender.noteHandSelection()
			assertNull(HandVelocityRender.handMotion.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, motion()))
			HandVelocityRender.endHandFrame()
			HandVelocityWriterBindings.clearFrame()

			// Frame 2: unchanged selection. The continuous pair composes a valid reprojection at
			// draw time, before the frame boundary commits anything.
			HandVelocityRender.beginHandFrame(projection)
			HandVelocityWriterBindings.beginHand(HandSlot.MAIN_HAND)
			HandVelocityRender.captureHandPose(
				HandSlot.MAIN_HAND, "sword", HandVelocityRender.frameProjection(), modelView, Matrix4f(pose).rotateZ(0.4f),
			)
			HandVelocityWriterBindings.endHand()
			HandVelocityWriterBindings.beginHand(HandSlot.OFF_HAND)
			HandVelocityRender.captureHandPose(
				HandSlot.OFF_HAND, "torch", HandVelocityRender.frameProjection(), modelView, Matrix4f(pose).rotateZ(0.3f),
			)
			HandVelocityWriterBindings.endHand()
			HandVelocityRender.noteHandSelection()
			assertTrue(HandVelocityRender.handMotion.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, motion()) != null)
			HandVelocityRender.endHandFrame()
			HandVelocityWriterBindings.clearFrame()

			// Frame 3: the selection changes (a bow starts charging and drops the off hand). The
			// surviving hand's item instance is unchanged, yet the frame is already invalidated
			// at draw time - the classification must not wait for the frame boundary.
			HandVelocityRender.beginHandFrame(projection)
			HandVelocityWriterBindings.beginHand(HandSlot.MAIN_HAND)
			HandVelocityRender.captureHandPose(
				HandSlot.MAIN_HAND, "sword", HandVelocityRender.frameProjection(), modelView, Matrix4f(pose).rotateZ(0.5f),
			)
			HandVelocityWriterBindings.endHand()
			HandVelocityRender.noteHandSelection()
			assertNull(HandVelocityRender.handMotion.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, motion()))
			HandVelocityRender.endHandFrame()
			HandVelocityWriterBindings.clearFrame()

			// Frame 4: the new selection is the baseline, so the continuous frame is valid again.
			HandVelocityRender.beginHandFrame(projection)
			HandVelocityWriterBindings.beginHand(HandSlot.MAIN_HAND)
			HandVelocityRender.captureHandPose(
				HandSlot.MAIN_HAND, "sword", HandVelocityRender.frameProjection(), modelView, Matrix4f(pose).rotateZ(0.6f),
			)
			HandVelocityWriterBindings.endHand()
			HandVelocityRender.noteHandSelection()
			assertTrue(HandVelocityRender.handMotion.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, motion()) != null)
			HandVelocityRender.endHandFrame()
			HandVelocityWriterBindings.clearFrame()
		} finally {
			HandVelocityRender.endHandFrame()
			HandVelocityRender.resetState()
			HandVelocityWriterBindings.clearFrame()
		}
	}

	@Test
	fun `an ineligible draw after an eligible hand draw forces a fresh untagged draw`() {
		HandVelocityWriterBindings.clearFrame()
		val staged = StagedVertexBuffer({ "hand-transition-test" }, 256)
		try {
			val submit = Any()
			HandVelocityWriterBindings.beginHand(HandSlot.MAIN_HAND)
			HandVelocityWriterBindings.bindSubmit(submit)
			HandVelocityWriterBindings.endHand()

			// The eligible hand draw first: tagged with the slot.
			HandVelocityWriterBindings.beginSubmit(submit, HandPoseDomain.Item)
			assertTrue(HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture)))
			val handDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val handInfo = emptyExecuteInfo()
			HandVelocityWriterBindings.bindDraw(handDraw)
			HandVelocityWriterBindings.bindExecuteInfo(handDraw, handInfo)
			assertSame(HandSlot.MAIN_HAND, HandVelocityWriterBindings.executeInfoHandSlot(handInfo))
			HandVelocityWriterBindings.endDraw()
			HandVelocityWriterBindings.endSubmit()

			// A foreign submit with the same render type follows in the same group: the
			// transition away from the eligible hand draw forces a fresh draw, and the foreign
			// geometry must not inherit the hand slot or the hand's reprojection.
			val foreign = Any()
			HandVelocityWriterBindings.bindSubmit(foreign)
			HandVelocityWriterBindings.beginSubmit(foreign, HandPoseDomain.Item)
			assertTrue(HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture)))
			val foreignDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val foreignInfo = emptyExecuteInfo()
			HandVelocityWriterBindings.bindDraw(foreignDraw)
			HandVelocityWriterBindings.bindExecuteInfo(foreignDraw, foreignInfo)
			assertNull(HandVelocityWriterBindings.executeInfoHandSlot(foreignInfo))
			assertNotEquals(handDraw, foreignDraw)
			HandVelocityWriterBindings.endDraw()
			HandVelocityWriterBindings.endSubmit()

			// A hand draw re-entering after the foreign draw starts its own fresh tagged draw
			// instead of consolidating into the untagged foreign draw.
			HandVelocityWriterBindings.beginSubmit(submit, HandPoseDomain.Item)
			assertTrue(HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture)))
			val secondHandDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val secondHandInfo = emptyExecuteInfo()
			HandVelocityWriterBindings.bindDraw(secondHandDraw)
			HandVelocityWriterBindings.bindExecuteInfo(secondHandDraw, secondHandInfo)
			assertSame(HandSlot.MAIN_HAND, HandVelocityWriterBindings.executeInfoHandSlot(secondHandInfo))
			assertNotEquals(foreignDraw, secondHandDraw)
			HandVelocityWriterBindings.endDraw()
			HandVelocityWriterBindings.endSubmit()
		} finally {
			HandVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	@Test
	fun `a foreign draw after an eligible hand draw cannot reuse the hand draw through the reorder`() {
		HandVelocityWriterBindings.clearFrame()
		val drawRenderTypes = mutableListOf<Any>()
		val preparedType = Any()
		val staged = StagedVertexBuffer({ "hand-reorder-test" }, 256)
		try {
			val submit = Any()
			HandVelocityWriterBindings.beginHand(HandSlot.MAIN_HAND)
			HandVelocityWriterBindings.bindSubmit(submit)
			HandVelocityWriterBindings.endHand()

			// The eligible hand draw: staged fresh, tagged with the slot.
			HandVelocityWriterBindings.beginSubmit(submit, HandPoseDomain.Item)
			assertTrue(HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture)))
			assertEquals(-1, HandVelocityWriterBindings.consolidationIndex(drawRenderTypes, preparedType))
			drawRenderTypes += preparedType
			val handDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val handInfo = emptyExecuteInfo()
			HandVelocityWriterBindings.bindDraw(handDraw)
			HandVelocityWriterBindings.bindExecuteInfo(handDraw, handInfo)
			assertSame(HandSlot.MAIN_HAND, HandVelocityWriterBindings.executeInfoHandSlot(handInfo))
			HandVelocityWriterBindings.endDraw()
			HandVelocityWriterBindings.endSubmit()

			// A foreign submit with the same prepared render type: the transition draw must
			// defeat the reorder reuse, or the foreign geometry would land in the hand draw and
			// inherit its reprojection.
			val foreign = Any()
			HandVelocityWriterBindings.bindSubmit(foreign)
			HandVelocityWriterBindings.beginSubmit(foreign, HandPoseDomain.Item)
			assertTrue(HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture)))
			assertTrue(HandVelocityWriterBindings.suppressConsolidation(), "transition draw must defeat reorder reuse")
			assertEquals(-1, HandVelocityWriterBindings.consolidationIndex(drawRenderTypes, preparedType))
			drawRenderTypes += preparedType
			val foreignDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val foreignInfo = emptyExecuteInfo()
			HandVelocityWriterBindings.bindDraw(foreignDraw)
			HandVelocityWriterBindings.bindExecuteInfo(foreignDraw, foreignInfo)
			assertNull(HandVelocityWriterBindings.executeInfoHandSlot(foreignInfo))
			HandVelocityWriterBindings.endDraw()
			HandVelocityWriterBindings.endSubmit()

			// After the transition, further foreign submits of the same render type consolidate
			// on the safe untagged draw through its private index, never on the hand draw.
			HandVelocityWriterBindings.beginSubmit(foreign, HandPoseDomain.Item)
			assertFalse(HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture)))
			assertFalse(HandVelocityWriterBindings.suppressConsolidation())
			assertEquals(1, HandVelocityWriterBindings.consolidationIndex(drawRenderTypes, preparedType))
			HandVelocityWriterBindings.endDraw()
			HandVelocityWriterBindings.endSubmit()

			// A hand draw after the foreign draws starts a fresh tagged draw again.
			HandVelocityWriterBindings.beginSubmit(submit, HandPoseDomain.Item)
			assertTrue(HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture)))
			val secondHandDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val secondHandInfo = emptyExecuteInfo()
			HandVelocityWriterBindings.bindDraw(secondHandDraw)
			HandVelocityWriterBindings.bindExecuteInfo(secondHandDraw, secondHandInfo)
			assertSame(HandSlot.MAIN_HAND, HandVelocityWriterBindings.executeInfoHandSlot(secondHandInfo))
			assertNotEquals(handDraw, secondHandDraw)
			HandVelocityWriterBindings.endDraw()
			HandVelocityWriterBindings.endSubmit()
		} finally {
			HandVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	@Test
	fun `hand history never aliases the entity object history`() {
		val objectMotion = ObjectMotionState()
		val hand = HandMotionState()
		hand.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", Matrix4f().translate(0f, 0f, -0.5f))
		hand.commit()
		hand.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", Matrix4f().translate(0f, 0f, -0.5f))

		// An entity with a numerically identical domain key moves independently: the hand's
		// captures and frame boundary never touch the entity history and vice versa.
		objectMotion.capture(0, 1.0, 2.0, 3.0)
		assertNull(objectMotion.displacement(0))
		hand.commit()
		assertNull(objectMotion.displacement(0), "the hand frame boundary must not publish entity captures")
		objectMotion.capture(0, 1.5, 2.0, 3.0)
		objectMotion.publish()
		assertTrue(objectMotion.previous(0) != null)
		hand.capture(HandSlot.MAIN_HAND, HandPoseDomain.Item, "sword", Matrix4f().translate(0f, 0f, -0.5f))
		assertTrue(
			hand.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, motion()) != null,
			"entity publishing must not disturb the hand history",
		)
	}

	@Test
	fun `the submission capture composes projection model-view and pose into the clip matrix`() {
		// The mixin-facing seam: the frame projection captured at the renderItemInHand wrap, the
		// model-view stack, and the pose stack at the submission seam.
		val projection = Matrix4f().setPerspective(1.2f, 16f / 9f, 100f, 0.05f, true)
		val modelView = Matrix4f().rotateY(0.3f)
		val pose = Matrix4f().rotateX(0.2f).translate(0f, -0.2f, -0.5f)
		val expectedFirst = Matrix4f(projection).mul(modelView).mul(pose)
		val expectedSecond = Matrix4f(projection).mul(modelView).mul(Matrix4f(pose).rotateZ(0.4f))

		HandVelocityRender.resetState()
		try {
			HandVelocityRender.beginHandFrame(projection)
			HandVelocityRender.captureHandPose(HandSlot.MAIN_HAND, "sword", HandVelocityRender.frameProjection(), modelView, pose)
			HandVelocityRender.endHandFrame()
			HandVelocityRender.beginHandFrame(projection)
			HandVelocityRender.captureHandPose(
				HandSlot.MAIN_HAND, "sword", HandVelocityRender.frameProjection(), modelView, Matrix4f(pose).rotateZ(0.4f),
			)
			val reprojection = HandVelocityRender.handMotion.reprojection(HandSlot.MAIN_HAND, HandPoseDomain.Item, motion())
			val expected = Matrix4f(expectedFirst).mul(Matrix4f(expectedSecond).invert())
			assertTrue(expected.equals(reprojection!!, 1e-4f))
		} finally {
			HandVelocityRender.endHandFrame()
			HandVelocityRender.resetState()
		}
	}

	@Test
	fun `the hand slot flows from the submission bracket through draw to execute info`() {
		HandVelocityWriterBindings.clearFrame()
		val staged = StagedVertexBuffer({ "hand-identity-test" }, 256)
		try {
			val submit = Any()
			HandVelocityWriterBindings.beginHand(HandSlot.MAIN_HAND)
			HandVelocityWriterBindings.bindSubmit(submit)
			HandVelocityWriterBindings.endHand()
			assertSame(HandSlot.MAIN_HAND, HandVelocityWriterBindings.submitHandSlot(submit))

			HandVelocityWriterBindings.beginSubmit(submit, HandPoseDomain.Item)
			assertTrue(HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture)))
			val draw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val info = emptyExecuteInfo()
			HandVelocityWriterBindings.bindDraw(draw)
			HandVelocityWriterBindings.bindExecuteInfo(draw, info)
			HandVelocityWriterBindings.endDraw()
			HandVelocityWriterBindings.endSubmit()

			assertSame(HandSlot.MAIN_HAND, HandVelocityWriterBindings.executeInfoHandSlot(info))
			assertSame(HandPoseDomain.Item, HandVelocityWriterBindings.executeInfoHandDomain(info))
		} finally {
			HandVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	@Test
	fun `a slot transition forces a fresh draw while the same slot consolidates`() {
		HandVelocityWriterBindings.clearFrame()
		val staged = StagedVertexBuffer({ "hand-boundary-test" }, 256)
		try {
			val main = Any()
			val off = Any()
			HandVelocityWriterBindings.beginHand(HandSlot.MAIN_HAND)
			HandVelocityWriterBindings.bindSubmit(main)
			HandVelocityWriterBindings.endHand()
			HandVelocityWriterBindings.beginHand(HandSlot.OFF_HAND)
			HandVelocityWriterBindings.bindSubmit(off)
			HandVelocityWriterBindings.endHand()

			// Same slot, consecutive geometry: one configuration covers both, so the draw is
			// reusable and the boundary answers false.
			HandVelocityWriterBindings.beginSubmit(main, HandPoseDomain.Item)
			assertTrue(HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture)))
			val mainDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			HandVelocityWriterBindings.bindDraw(mainDraw)
			HandVelocityWriterBindings.endDraw()
			assertFalse(HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture)))
			HandVelocityWriterBindings.endDraw()
			HandVelocityWriterBindings.endSubmit()

			// Slot transition: the reusable draw belongs to the other hand and carries that
			// hand's configuration, so the boundary forces a fresh draw.
			HandVelocityWriterBindings.beginSubmit(off, HandPoseDomain.Item)
			assertTrue(HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture)))
			val offDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			HandVelocityWriterBindings.bindDraw(offDraw)
			HandVelocityWriterBindings.endDraw()
			HandVelocityWriterBindings.endSubmit()

			assertSame(HandSlot.MAIN_HAND, HandVelocityWriterBindings.executeInfoHandSlot(emptyExecuteInfo().let { info ->
				HandVelocityWriterBindings.bindExecuteInfo(mainDraw, info)
				info
			}))
			assertSame(HandSlot.OFF_HAND, HandVelocityWriterBindings.executeInfoHandSlot(emptyExecuteInfo().let { info ->
				HandVelocityWriterBindings.bindExecuteInfo(offDraw, info)
				info
			}))
		} finally {
			HandVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	@Test
	fun `a pose domain transition forces a fresh draw while the same domain consolidates`() {
		HandVelocityWriterBindings.clearFrame()
		val staged = StagedVertexBuffer({ "hand-domain-test" }, 256)
		try {
			val submit = Any()
			HandVelocityWriterBindings.beginHand(HandSlot.MAIN_HAND)
			HandVelocityWriterBindings.bindSubmit(submit)
			HandVelocityWriterBindings.endHand()

			// Same slot, same arm part, consecutive geometry: one configuration and one
			// reprojection cover both, so the draw is reusable.
			HandVelocityWriterBindings.beginSubmit(submit, HandPoseDomain.Arm("rightArm"))
			assertTrue(HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture)))
			val rightDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			HandVelocityWriterBindings.bindDraw(rightDraw)
			HandVelocityWriterBindings.endDraw()
			assertFalse(HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture)))
			HandVelocityWriterBindings.endDraw()
			HandVelocityWriterBindings.endSubmit()

			// Same slot, different arm part (the two-handed map's other arm): the reusable draw
			// carries the other arm's reprojection, so the boundary forces a fresh draw.
			HandVelocityWriterBindings.beginSubmit(submit, HandPoseDomain.Arm("leftArm"))
			assertTrue(HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture)))
			val leftDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			HandVelocityWriterBindings.bindDraw(leftDraw)
			HandVelocityWriterBindings.endDraw()
			HandVelocityWriterBindings.endSubmit()

			assertEquals(HandPoseDomain.Arm("rightArm"), HandVelocityWriterBindings.executeInfoHandDomain(emptyExecuteInfo().let { info ->
				HandVelocityWriterBindings.bindExecuteInfo(rightDraw, info)
				info
			}))
			assertEquals(HandPoseDomain.Arm("leftArm"), HandVelocityWriterBindings.executeInfoHandDomain(emptyExecuteInfo().let { info ->
				HandVelocityWriterBindings.bindExecuteInfo(leftDraw, info)
				info
			}))
		} finally {
			HandVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	@Test
	fun `ineligible and foreign draws carry no hand identity`() {
		HandVelocityWriterBindings.clearFrame()
		val staged = StagedVertexBuffer({ "hand-ineligible-test" }, 256)
		try {
			// No submission bracket: the submit record carries no slot and stages nothing.
			val foreign = Any()
			HandVelocityWriterBindings.bindSubmit(foreign)
			assertNull(HandVelocityWriterBindings.submitHandSlot(foreign))
			HandVelocityWriterBindings.beginSubmit(foreign, HandPoseDomain.Item)
			assertFalse(HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture)))
			val draw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			HandVelocityWriterBindings.bindDraw(draw)
			assertNull(HandVelocityWriterBindings.executeInfoHandSlot(emptyExecuteInfo().let { info ->
				HandVelocityWriterBindings.bindExecuteInfo(draw, info)
				info
			}))
			HandVelocityWriterBindings.endDraw()
			HandVelocityWriterBindings.endSubmit()

			// A bracketed submit on a foreign pipeline (the item's foil glint) stages no
			// hand-eligible draw.
			HandVelocityWriterBindings.beginHand(HandSlot.MAIN_HAND)
			val glint = Any()
			HandVelocityWriterBindings.bindSubmit(glint)
			HandVelocityWriterBindings.endHand()
			HandVelocityWriterBindings.beginSubmit(glint, HandPoseDomain.Item)
			assertFalse(HandVelocityWriterBindings.beginDraw(RenderTypes.glint()))
			HandVelocityWriterBindings.endDraw()
			HandVelocityWriterBindings.endSubmit()
		} finally {
			HandVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	@Test
	fun `the reorder lookup never merges across hand slots`() {
		HandVelocityWriterBindings.clearFrame()
		try {
			// Outside the hand window the lookup is exactly vanilla indexOf.
			assertEquals(1, HandVelocityWriterBindings.consolidationIndex(listOf("a", "b"), "b"))

			// While a hand submit is staged, a reorder reuse could merge two hands' geometry
			// into one draw under one configuration: always a fresh draw instead.
			val submit = Any()
			HandVelocityWriterBindings.beginHand(HandSlot.MAIN_HAND)
			HandVelocityWriterBindings.bindSubmit(submit)
			HandVelocityWriterBindings.endHand()
			HandVelocityWriterBindings.beginSubmit(submit, HandPoseDomain.Item)
			assertEquals(-1, HandVelocityWriterBindings.consolidationIndex(listOf("a", "b"), "b"))
			HandVelocityWriterBindings.endSubmit()
			assertEquals(1, HandVelocityWriterBindings.consolidationIndex(listOf("a", "b"), "b"))
		} finally {
			HandVelocityWriterBindings.clearFrame()
		}
	}

	@Test
	fun `only the owned hand pipeline families are eligible for the hand writer`() {
		assertTrue(HandVelocityRender.isHandPipeline(RenderPipelines.ITEM_CUTOUT))
		assertTrue(HandVelocityRender.isHandPipeline(RenderPipelines.ITEM_TRANSLUCENT))
		// The bare player arm renders through the entity family; the map's custom geometry and
		// labels through the world-text family.
		assertTrue(HandVelocityRender.isHandPipeline(RenderPipelines.ENTITY_TRANSLUCENT))
		assertTrue(HandVelocityRender.isHandPipeline(RenderPipelines.TEXT))
		assertFalse(HandVelocityRender.isHandPipeline(RenderPipelines.SOLID_BLOCK))
		assertFalse(HandVelocityRender.isHandPipeline(RenderPipelines.SOLID_TERRAIN))
		assertFalse(HandVelocityRender.isHandPipeline(RenderPipelines.GUI_TEXT))
	}

	@Test
	fun `the hand twin preserves the source descriptor and applies the always-pass no-write depth policy`() {
		for (source in listOf(RenderPipelines.ITEM_CUTOUT, RenderPipelines.ITEM_TRANSLUCENT)) {
			val twin = writerTwin(source, VelocityWriter.HAND, HandVelocityRender.HAND_DEPTH)

			assertSame(source.vertexShader, twin.vertexShader)
			assertEquals(source.shaderDefines, twin.shaderDefines)
			assertSame(HandVelocityRender.HAND_DEPTH, twin.depthStencilState)
			assertEquals(CompareOp.ALWAYS_PASS, twin.depthStencilState!!.depthTest())
			assertFalse(twin.depthStencilState!!.writeDepth(), "the hand never writes the scene depth, so the fill keeps world depth")
			assertSame(source.polygonMode, twin.polygonMode)
			assertEquals(source.isCull, twin.isCull)
			assertSame(source.primitiveTopology, twin.primitiveTopology)
			assertEquals(source.getVertexFormatBindings().size, twin.getVertexFormatBindings().size)

			val twinTargets = twin.colorTargetStates
			assertEquals(2, twinTargets.size)
			assertSame(source.colorTargetStates[0], twinTargets[0])
			assertVelocityTarget(twinTargets[1]!!)

			assertEquals(source.bindGroupLayouts.size + 1, twin.bindGroupLayouts.size)
			for (index in source.bindGroupLayouts.indices) {
				assertSame(source.bindGroupLayouts[index], twin.bindGroupLayouts[index])
			}
			assertSame(HandVelocityRender.LAYOUT, twin.bindGroupLayouts.last())
			assertSame(HandVelocityRender.FRAGMENT_SHADER, twin.fragmentShader)

			// Cached per source, at the hand writer's own location, and distinct from the plain
			// twin and from a twin built without the hand depth policy.
			assertSame(twin, writerTwin(source, VelocityWriter.HAND, HandVelocityRender.HAND_DEPTH))
			assertEquals(
				Identifier.fromNamespaceAndPath("mc-dlss", "velocity/hand/${source.location.path.removePrefix("pipeline/")}"),
				twin.location,
			)
			assertNotEquals(twin.location, velocityTwin(source).location)
		}
	}

	@Test
	fun `the arm twin preserves the entity family through the hand depth policy`() {
		val source = RenderPipelines.ENTITY_TRANSLUCENT
		val twin = writerTwin(source, VelocityWriter.HAND_ARM, HandVelocityRender.HAND_DEPTH)

		assertSame(source.vertexShader, twin.vertexShader)
		assertEquals(source.shaderDefines, twin.shaderDefines)
		assertSame(HandVelocityRender.HAND_DEPTH, twin.depthStencilState)
		assertEquals(CompareOp.ALWAYS_PASS, twin.depthStencilState!!.depthTest())
		assertFalse(twin.depthStencilState!!.writeDepth())
		assertSame(source.polygonMode, twin.polygonMode)
		assertEquals(source.isCull, twin.isCull)
		assertSame(source.primitiveTopology, twin.primitiveTopology)
		assertEquals(source.getVertexFormatBindings().size, twin.getVertexFormatBindings().size)

		val twinTargets = twin.colorTargetStates
		assertEquals(2, twinTargets.size)
		assertSame(source.colorTargetStates[0], twinTargets[0])
		assertVelocityTarget(twinTargets[1]!!)

		assertEquals(source.bindGroupLayouts.size + 1, twin.bindGroupLayouts.size)
		for (index in source.bindGroupLayouts.indices) {
			assertSame(source.bindGroupLayouts[index], twin.bindGroupLayouts[index])
		}
		// The arm twin shares the safe entity velocity fragment and payload layout, which
		// mirror the source entity color logic under the source's own defines; the draw and the
		// pose history stay hand-owned.
		assertSame(EntityVelocityUniforms.FRAGMENT_SHADER, twin.fragmentShader)
		assertSame(EntityVelocityUniforms.LAYOUT, twin.bindGroupLayouts.last())

		assertSame(twin, writerTwin(source, VelocityWriter.HAND_ARM, HandVelocityRender.HAND_DEPTH))
		assertEquals(
			Identifier.fromNamespaceAndPath("mc-dlss", "velocity/hand_arm/entity_translucent"),
			twin.location,
		)
		assertNotEquals(twin.location, writerTwin(source, VelocityWriter.ENTITY).location)
	}

	@Test
	fun `the map twin preserves the text family through the hand depth policy`() {
		val source = RenderPipelines.TEXT
		val twin = writerTwin(source, VelocityWriter.HAND_TEXT, HandVelocityRender.HAND_DEPTH)

		assertSame(source.vertexShader, twin.vertexShader)
		assertEquals(source.shaderDefines, twin.shaderDefines)
		assertSame(HandVelocityRender.HAND_DEPTH, twin.depthStencilState)
		assertEquals(CompareOp.ALWAYS_PASS, twin.depthStencilState!!.depthTest())
		assertFalse(twin.depthStencilState!!.writeDepth())
		assertSame(source.polygonMode, twin.polygonMode)
		assertEquals(source.isCull, twin.isCull)
		assertSame(source.primitiveTopology, twin.primitiveTopology)
		assertEquals(source.getVertexFormatBindings().size, twin.getVertexFormatBindings().size)

		val twinTargets = twin.colorTargetStates
		assertEquals(2, twinTargets.size)
		assertSame(source.colorTargetStates[0], twinTargets[0])
		assertVelocityTarget(twinTargets[1]!!)

		assertEquals(source.bindGroupLayouts.size + 1, twin.bindGroupLayouts.size)
		for (index in source.bindGroupLayouts.indices) {
			assertSame(source.bindGroupLayouts[index], twin.bindGroupLayouts[index])
		}
		assertSame(HandVelocityRender.TEXT_FRAGMENT_SHADER, twin.fragmentShader)
		assertSame(HandVelocityRender.LAYOUT, twin.bindGroupLayouts.last())

		assertSame(twin, writerTwin(source, VelocityWriter.HAND_TEXT, HandVelocityRender.HAND_DEPTH))
		assertEquals(
			Identifier.fromNamespaceAndPath("mc-dlss", "velocity/hand_text/text"),
			twin.location,
		)
	}

	@Test
	fun `eligible open velocity-mrt phase admits the writer control seam and ineligible routes fall through`() {
		val runtime = velocityRuntime()
		val phase = worldPhase(runtime)
		val staged = StagedVertexBuffer({ "hand-control-test" }, 256)
		try {
			renderFrame(phase, fakeMainTarget())
			phase.prepare(true, fakeMainTarget(), cameraSample())
			phase.begin(true, fakeMainTarget())

			val itemInfo = handBoundInfo(staged, HandPoseDomain.Item)
			val cutout = PreparedRenderType(RenderPipelines.ITEM_CUTOUT, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
			assertTrue(HandVelocityRender.canDraw(cutout, itemInfo, phase))

			// The bare player arm: an entity-family draw carrying the hand slot and an arm pose
			// domain is admitted on the open velocity-MRT scene.
			val armInfo = handBoundInfo(staged, HandPoseDomain.Arm(Any()))
			val arm = PreparedRenderType(RenderPipelines.ENTITY_TRANSLUCENT, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
			assertTrue(HandVelocityRender.canDraw(arm, armInfo, phase))

			// The map's custom geometry: a world-text-family draw carrying the hand slot and the
			// map pose domain is admitted.
			val mapInfo = handBoundInfo(staged, HandPoseDomain.Map)
			val map = PreparedRenderType(RenderPipelines.TEXT, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
			assertTrue(HandVelocityRender.canDraw(map, mapInfo, phase))

			// GUI text stays out: its vertex format is the GUI text format, not the world text
			// format the map draws with.
			val guiText = PreparedRenderType(RenderPipelines.GUI_TEXT, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
			assertFalse(HandVelocityRender.canDraw(guiText, mapInfo, phase))

			// The translucent item render type resolves the item/entity target: never a scene
			// velocity route.
			val translucent = PreparedRenderType(RenderPipelines.ITEM_TRANSLUCENT, OutputTarget.ITEM_ENTITY_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
			assertFalse(HandVelocityRender.canDraw(translucent, itemInfo, phase))

			// Unsupported pipelines fall through to their exact source draw.
			val terrain = PreparedRenderType(RenderPipelines.SOLID_TERRAIN, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
			assertFalse(HandVelocityRender.canDraw(terrain, itemInfo, phase))

			// A draw without a bound hand slot (an entity or screen-effect draw sharing the
			// batching seam) falls through even on the eligible pipeline.
			assertFalse(HandVelocityRender.canDraw(cutout, emptyExecuteInfo(), phase))

			// The draw replacement falls through without a live ClientRuntime phase: the phase
			// gate answers false before anything can touch a device, so the draw never throws.
			assertFalse(
				HandVelocityRender.draw(cutout, itemInfo),
				"headless: the draw must answer false at the phase gate, never throw",
			)
		} finally {
			HandVelocityWriterBindings.clearFrame()
			staged.close()
			if (phase.isOpen) phase.end()
		}
	}

	@Test
	fun `map custom and text submit records resolve the hand slot and map domain into the writer seam`() {
		// The real 26.2 map submit records, built exactly as the renderer does: custom geometry
		// with a pose/render-type/callback triple, and text with the label parameters. The
		// mod-owned binding copy must land the slot on both record types, and the production
		// staging path must resolve the Map pose domain from the record types alone.
		val runtime = velocityRuntime()
		val phase = worldPhase(runtime)
		val staged = StagedVertexBuffer({ "hand-map-record-test" }, 256)
		try {
			renderFrame(phase, fakeMainTarget())
			phase.prepare(true, fakeMainTarget(), cameraSample())
			phase.begin(true, fakeMainTarget())

			val custom = CustomFeatureRenderer.Submit(PoseStack().last(), RenderTypes.itemCutout(texture)) { _, _ -> }
			val text = TextFeatureRenderer.Submit(
				Matrix4f(),
				0f,
				0f,
				FormattedCharSequence.EMPTY,
				false,
				Font.DisplayMode.NORMAL,
				0,
				0,
				0,
				0,
			)

			// A map-family submit outside the hand window carries no slot: the record types are
			// shared with entity and screen-effect geometry, so the constructor copy must be
			// selective on the open bracket.
			HandVelocityWriterBindings.bindSubmit(custom)
			assertNull(HandVelocityWriterBindings.submitHandSlot(custom))

			// Inside the window the copy lands the slot on both record types.
			HandVelocityWriterBindings.beginHand(HandSlot.MAIN_HAND)
			HandVelocityWriterBindings.bindSubmit(custom)
			HandVelocityWriterBindings.bindSubmit(text)
			HandVelocityWriterBindings.endHand()
			assertSame(HandSlot.MAIN_HAND, HandVelocityWriterBindings.submitHandSlot(custom))
			assertSame(HandSlot.MAIN_HAND, HandVelocityWriterBindings.submitHandSlot(text))

			// The production staging path resolves the Map domain from the record types and tags
			// the staged draw; the writer control seam then admits the draw on the text family.
			HandVelocityWriterBindings.beginSubmit(custom)
			assertTrue(HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture)))
			val customDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val customInfo = emptyExecuteInfo()
			HandVelocityWriterBindings.bindDraw(customDraw)
			HandVelocityWriterBindings.bindExecuteInfo(customDraw, customInfo)
			assertSame(HandSlot.MAIN_HAND, HandVelocityWriterBindings.executeInfoHandSlot(customInfo))
			assertEquals(HandPoseDomain.Map, HandVelocityWriterBindings.executeInfoHandDomain(customInfo))
			HandVelocityWriterBindings.endDraw()
			HandVelocityWriterBindings.endSubmit()

			HandVelocityWriterBindings.beginSubmit(text)
			// Same slot, same Map domain, consecutive geometry: the text record consolidates onto
			// the custom record's draw exactly as consecutive same-domain submits do, so the
			// boundary answers false and the reused draw carries both records' identity.
			assertFalse(HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture)))
			val textInfo = emptyExecuteInfo()
			HandVelocityWriterBindings.bindDraw(customDraw)
			HandVelocityWriterBindings.bindExecuteInfo(customDraw, textInfo)
			assertSame(HandSlot.MAIN_HAND, HandVelocityWriterBindings.executeInfoHandSlot(textInfo))
			assertEquals(HandPoseDomain.Map, HandVelocityWriterBindings.executeInfoHandDomain(textInfo))
			HandVelocityWriterBindings.endDraw()
			HandVelocityWriterBindings.endSubmit()

			// Both record types reach the eligible writer control seam: a world-text-family draw
			// carrying the hand slot and the Map pose domain is admitted on the open velocity-MRT
			// scene.
			val map = PreparedRenderType(RenderPipelines.TEXT, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
			assertTrue(HandVelocityRender.canDraw(map, customInfo, phase))
			assertTrue(HandVelocityRender.canDraw(map, textInfo, phase))
		} finally {
			HandVelocityWriterBindings.clearFrame()
			staged.close()
			if (phase.isOpen) phase.end()
		}
	}

	@Test
	fun `per-submit staging brackets each map record alone so a foreign group leader neither gates nor inherits the hand slot`() {
		// The staging mixins bracket each submit's geometry separately, never the whole group:
		// mapped 26.2 feature lists mix hand submits with unrelated entity or screen-effect
		// custom/text submits, so a whole-group bracket keyed on the first submit would lose the
		// hand slot when a foreign record leads the group, or leak it to every group member when
		// a hand record leads. Each record must stage (or refuse) its own slot.
		val staged = StagedVertexBuffer({ "hand-per-submit-test" }, 256)
		try {
			val foreign = CustomFeatureRenderer.Submit(PoseStack().last(), RenderTypes.itemCutout(texture)) { _, _ -> }
			val handText = TextFeatureRenderer.Submit(
				Matrix4f(),
				0f,
				0f,
				FormattedCharSequence.EMPTY,
				false,
				Font.DisplayMode.NORMAL,
				0,
				0,
				0,
				0,
			)
			val handCustom = CustomFeatureRenderer.Submit(PoseStack().last(), RenderTypes.itemCutout(texture)) { _, _ -> }
			HandVelocityWriterBindings.beginHand(HandSlot.MAIN_HAND)
			HandVelocityWriterBindings.bindSubmit(handText)
			HandVelocityWriterBindings.bindSubmit(handCustom)
			HandVelocityWriterBindings.endHand()

			// A foreign record leads the group: its own bracket installs nothing, so the old
			// first-submit group bracket would have gated the whole group and dropped the hand
			// submits that follow.
			HandVelocityWriterBindings.beginSubmit(foreign)
			assertFalse(HandVelocityWriterBindings.isActive(), "a slotless submit stages nothing")
			assertFalse(HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture)))
			val foreignDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val foreignInfo = emptyExecuteInfo()
			HandVelocityWriterBindings.bindDraw(foreignDraw)
			HandVelocityWriterBindings.bindExecuteInfo(foreignDraw, foreignInfo)
			assertNull(HandVelocityWriterBindings.executeInfoHandSlot(foreignInfo))
			assertNull(HandVelocityWriterBindings.executeInfoHandDomain(foreignInfo))
			HandVelocityWriterBindings.endDraw()
			HandVelocityWriterBindings.endSubmit()

			// The bound hand record that follows stages its slot for exactly its own geometry,
			// starting a fresh tagged draw after the foreign draw.
			HandVelocityWriterBindings.beginSubmit(handText)
			assertTrue(HandVelocityWriterBindings.isActive())
			assertTrue(HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture)))
			val handDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val handInfo = emptyExecuteInfo()
			HandVelocityWriterBindings.bindDraw(handDraw)
			HandVelocityWriterBindings.bindExecuteInfo(handDraw, handInfo)
			assertSame(HandSlot.MAIN_HAND, HandVelocityWriterBindings.executeInfoHandSlot(handInfo))
			assertEquals(HandPoseDomain.Map, HandVelocityWriterBindings.executeInfoHandDomain(handInfo))
			assertNotEquals(foreignDraw, handDraw)
			HandVelocityWriterBindings.endDraw()
			HandVelocityWriterBindings.endSubmit()
			// The bracket closes when the submit's geometry ends: nothing leaks into the next
			// record of the group.
			assertFalse(HandVelocityWriterBindings.isActive())

			// The next bound record stages again: its per-submit bracket re-installs the same
			// slot and Map domain, so it consolidates onto the previous hand draw exactly as if
			// no bracket boundary sat between them.
			HandVelocityWriterBindings.beginSubmit(handCustom)
			assertTrue(HandVelocityWriterBindings.isActive())
			assertFalse(HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture)))
			val secondHandInfo = emptyExecuteInfo()
			HandVelocityWriterBindings.bindDraw(handDraw)
			HandVelocityWriterBindings.bindExecuteInfo(handDraw, secondHandInfo)
			assertSame(HandSlot.MAIN_HAND, HandVelocityWriterBindings.executeInfoHandSlot(secondHandInfo))
			assertEquals(HandPoseDomain.Map, HandVelocityWriterBindings.executeInfoHandDomain(secondHandInfo))
			HandVelocityWriterBindings.endDraw()
			HandVelocityWriterBindings.endSubmit()
		} finally {
			HandVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	@Test
	fun `vanilla camera-only and non-open phases keep the hand route unchanged`() {
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
			HandVelocityRender.canDraw(
				PreparedRenderType(RenderPipelines.ITEM_CUTOUT, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList()),
				info,
				cameraOnlyPhase,
			),
			"a camera-only phase offers no velocity view",
		)
		assertFalse(
			HandVelocityRender.draw(
				PreparedRenderType(RenderPipelines.ITEM_CUTOUT, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList()),
				info,
			),
		)

		// Vanilla: a session without DLSS keeps the hand draw on its exact source route.
		val vanillaPhase = worldPhase(velocityRuntime(withVelocity = true, enabled = false))
		assertFalse(
			HandVelocityRender.canDraw(
				PreparedRenderType(RenderPipelines.ITEM_CUTOUT, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList()),
				info,
				vanillaPhase,
			),
		)
	}

	/** Runs the bindings chain for one main-hand item draw and returns the bound execute info. */
	private fun handBoundInfo(staged: StagedVertexBuffer, domain: HandPoseDomain): StagedVertexBuffer.ExecuteInfo {
		HandVelocityWriterBindings.clearFrame()
		val submit = Any()
		HandVelocityWriterBindings.beginHand(HandSlot.MAIN_HAND)
		HandVelocityWriterBindings.bindSubmit(submit)
		HandVelocityWriterBindings.endHand()
		HandVelocityWriterBindings.beginSubmit(submit, domain)
		HandVelocityWriterBindings.beginDraw(RenderTypes.itemCutout(texture))
		val draw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
		val info = emptyExecuteInfo()
		HandVelocityWriterBindings.bindDraw(draw)
		HandVelocityWriterBindings.bindExecuteInfo(draw, info)
		HandVelocityWriterBindings.endDraw()
		HandVelocityWriterBindings.endSubmit()
		return info
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
