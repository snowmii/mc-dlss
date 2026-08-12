package me.snowmii.dlss.mrt

import me.snowmii.dlss.render.DlssFrameMotion
import org.joml.Matrix4f

/**
 * The two first-person hand domains, each with its own interpolated pose-history slots.
 *
 * The slots are independent in every respect: each captures its own poses, each commits its own
 * predecessors, and each classifies its own reset conditions. The writer resolves one slot per
 * hand draw, so main-hand geometry can never read an off-hand predecessor or vice versa.
 */
enum class HandSlot {
	MAIN_HAND,
	OFF_HAND,
}

/**
 * The pose domain one hand observation belongs to.
 *
 * `submitArmWithItem` renders up to three geometry families per hand, each with its own
 * frame-interpolated pose chain: the held item (`renderItem`, the `core/item` family), the bare
 * player arm (empty-hand and map branches, the `core/entity` family), and the map's custom
 * geometry (background, map texture, decorations, labels; the `core/text` family). The pose
 * histories are keyed per domain because one slot can render several pieces in one frame whose
 * poses differ - an arm and a map, or two arms - and each piece's reprojection must use its own
 * pose pair.
 *
 * The arm domain is keyed by the stable `ModelPart` instance the arm renders through, so the
 * right and left arms of the two-handed map keep separate histories while the same arm stays
 * continuous across frames. The map domain is a single slot per frame: every map piece's
 * additional transform (background scale, decoration rotation, label offset) is constant across
 * frames, so the composed `C_prev * inverse(C_cur)` of the map's base pose is the exact
 * reprojection for all of them.
 */
sealed class HandPoseDomain {
	/** The held item's pose chain (`renderItem` HEAD). */
	data object Item : HandPoseDomain()

	/** The map branch's custom-geometry pose chain (`renderMap` HEAD). */
	data object Map : HandPoseDomain()

	/** One rendered player-arm geometry lineage, keyed by the stable model-part instance. */
	data class Arm(val part: Any) : HandPoseDomain()
}

/**
 * One observed hand pose: the identity of the item that was rendered and the full matrix that
 * maps the piece's local coordinates into this frame's clip space.
 *
 * [clip] is captured as `projection * modelView * pose` - the transform the drawn geometry is
 * rasterized with up to constant inner transforms (item display transforms, the model part's own
 * pose, the map's fixed scales), which cancel in the reprojection. It is the frame's
 * interpolated render pose: every `Mth.lerp(frameInterp, ...)` value the hand renderer applied
 * (arm height, bob, attack anim, view-rotation difference) is already baked into it, and the
 * projection and model-view sides capture how the hand's clip positions change when the HUD
 * projection or the camera rotation change. A null [clip] is a failed capture (a throw in the
 * read seam) and classifies as a reset like any other invalid observation.
 *
 * [identity] is the rendered piece's stable identity - the `ItemStack` instance for the item and
 * map branches, the model part instance for the arm - compared by reference across frames. The
 * renderer only replaces the item instance when it decides the visible item changed (after the
 * swap animation), so a reference change is exactly the item-identity change the reset policy
 * names.
 */
private data class HandObservation(
	val identity: Any?,
	val clip: Matrix4f?,
)

/**
 * The previous-transform double buffer behind the hand/item velocity writer: one committed
 * frame of per-slot per-domain hand poses and one in-flight frame of captures, owned by the hand
 * domain and shared with nothing else.
 *
 * The lifecycle is the hand render window, which runs exactly once per frame inside
 * [me.snowmii.dlss.mixin.GameRendererWorldTargetMixin]'s wrap of `renderItemInHand`:
 *
 * 1. [capture] records each rendered hand piece's pose for the frame in flight, at its
 *    submission or staging seam, before the hand's staged draws execute.
 * 2. [noteSelection] records the frame's render selection - the set of hands `submitArmWithItem`
 *    was called for - as each hand bracket closes, so the value is complete before any draw is
 *    staged.
 * 3. The hand's draws read [reprojection] between capture and the frame boundary: the
 *    in-flight observation is this frame's pose, the committed one is the previous rendered
 *    frame's pose, and the frame's selection is compared against the committed one right there,
 *    at draw time.
 * 4. [commit] is the frame boundary, driven by the same wrap after the hand draw window
 *    finishes. Captures become predecessors; a slot or domain that was not captured this frame -
 *    the hand did not render: spectator mode, scoping, the two-handed map, a render selection
 *    that dropped the hand, or a branch that stopped rendering a piece - commits no predecessor,
 *    so the next observation of that slot/domain is a first observation again.
 *
 * The classification is entirely self-contained: a null result means the draw must write the
 * invalid sentinel. A missing committed observation (first frame, or a slot absent last frame),
 * an item-identity change, a render-selection change (a bow or crossbow that starts or stops
 * charging changes which hands render and jumps the surviving hand's pose chain even though its
 * item instance is unchanged - the pending selection is compared against the committed one at
 * draw time), a failed pose capture, or a missing/reset camera chain ([DlssFrameMotion.reset])
 * all answer null. The camera-chain condition is the phase's own
 * motion state: a vanilla frame, an abandoned phase, a world change, or a release all break
 * it, which is what makes the hand history safe to advance unconditionally - no read of it is
 * ever admitted past a broken camera chain.
 *
 * The hand domain is deliberately separate from [ObjectMotionState]: entity and moving-block
 * history is keyed by numeric ids, this state is keyed by [HandSlot] and [HandPoseDomain], so a
 * hand slot can never read or write an entity's or a block's predecessor no matter what ids
 * those domains carry. Single writer per frame by construction - one hand render window - so no
 * synchronization exists.
 */
class HandMotionState {
	private val inFlight = HashMap<HandSlot, HashMap<HandPoseDomain, HandObservation>>()
	private val committed = HashMap<HandSlot, HashMap<HandPoseDomain, HandObservation>>()

	/** The last committed frame's render selection, or null before the first committed frame. */
	private var selection: Set<HandSlot>? = null

	/** This frame's render selection, recorded by [noteSelection] as each hand bracket closes. */
	private var pendingSelection: Set<HandSlot>? = null

	/**
	 * Records [slot]'s [domain] interpolated render pose for the frame in flight. Does not become
	 * visible to [reprojection] as a predecessor until the next [commit]. A second capture of the
	 * same domain in one frame replaces the first: within one hand window every piece of one
	 * domain shares the domain's pose chain, so the last observation is the authoritative one.
	 */
	fun capture(slot: HandSlot, domain: HandPoseDomain, identity: Any?, clip: Matrix4f?) {
		inFlight.getOrPut(slot) { HashMap() }[domain] = HandObservation(identity, clip)
	}

	/**
	 * Records the frame's render selection - the set of hands the renderer chose to render -
	 * driven as each `submitArmWithItem` bracket closes, so the value is complete before any
	 * hand draw is staged. Whether it differs from the last committed frame's is decided when a
	 * draw reads [reprojection], not here: the surviving hand's pose chain jumps (a bow or
	 * crossbow that starts or stops charging drops the other hand and switches the surviving
	 * hand's branch) even when the item instance is unchanged.
	 */
	fun noteSelection(rendered: Set<HandSlot>) {
		pendingSelection = rendered
	}

	/**
	 * The frame boundary: this frame's captures become the predecessors of the next frame, and a
	 * slot or domain absent from this frame's captures loses its predecessor entirely, so the
	 * next observation of it is a first observation again (the hand-disappearance reset). The
	 * pending selection becomes the committed baseline; a frame whose hand window never ran
	 * (spectator, HUD hidden) keeps the previous baseline, so a selection change that happened
	 * while the hand was away is still detected on its return.
	 */
	fun commit() {
		committed.clear()
		committed.putAll(inFlight)
		inFlight.clear()
		if (pendingSelection != null) {
			selection = pendingSelection
		}
		pendingSelection = null
	}

	/**
	 * This frame's hand reprojection for [slot]'s [domain]: the matrix mapping this frame's clip
	 * position to the previous rendered frame's clip position, or null when the draw must write
	 * the invalid sentinel.
	 *
	 * A hand surface point is camera-attached: it sat at view position `C_prev * local` last
	 * frame and sits at `C_cur * local` this frame, so the reprojection is exactly
	 *
	 * ```
	 * M = C_prev * inverse(C_cur)
	 * ```
	 *
	 * with the camera's own translation cancelling entirely - the hand moves with the camera.
	 * The hand draws unjittered (the HUD projection is not jittered), so no jitter terms
	 * appear. A still pose (C_cur == C_prev) still returns the identity matrix - a valid,
	 * non-sentinel zero motion - because a hand that did not move is precisely the
	 * camera-attached case whose pixels stay put.
	 *
	 * Null when any reset condition holds: no committed predecessor (a first observation, or a
	 * slot absent from the previous frame), an item-identity change, a render-selection change
	 * (the pending selection differs from the committed one - evaluated here, at draw time),
	 * a failed pose capture, or a missing or reset camera chain. The writer writes the invalid
	 * classification on null.
	 */
	fun reprojection(slot: HandSlot, domain: HandPoseDomain, motion: DlssFrameMotion?): Matrix4f? {
		if (motion == null || motion.reset) {
			return null
		}
		// The selection comparison happens here, at draw time, against the set of hands this
		// frame's brackets captured: the classification can never be cleared out from under the
		// draws by an earlier frame boundary, no matter where in the frame the draws execute.
		if (pendingSelection != null && selection != null && pendingSelection != selection) {
			return null
		}
		val current = inFlight[slot]?.get(domain) ?: return null
		val previous = committed[slot]?.get(domain) ?: return null
		if (previous.identity !== current.identity) {
			return null
		}
		val clip = current.clip ?: return null
		val previousClip = previous.clip ?: return null
		return Matrix4f(previousClip).mul(Matrix4f(clip).invert())
	}
}
