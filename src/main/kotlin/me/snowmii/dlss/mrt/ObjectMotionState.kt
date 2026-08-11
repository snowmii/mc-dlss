package me.snowmii.dlss.mrt

import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitterOffset
import org.joml.Matrix4f
import org.joml.Vector3f

/**
 * One captured observation of a visible object.
 *
 * The capture seam reads the entity's *interpolated render position* - the doubles
 * `EntityRenderState.x/y/z` carry, which Minecraft 26.2 computes as
 * `Mth.lerp(partialTick, xOld/yOld/zOld, getX/Y/Z())`. The raw `Entity.getX/Y/Z()` getters are
 * current-tick positions, not the partial-tick pose the geometry is actually drawn at, so they
 * are rejected as equivalent capture input: publishing them would measure displacement between
 * the wrong poses and smear one frame of velocity. An extractor without the render state can
 * pass an explicit interpolation of the old and current tick positions with the same partial
 * tick instead.
 *
 * Positions are kept as doubles and only reduced to the float [Vector3f] the reprojection math
 * consumes when a displacement is derived. An immutable value object rather than a mutable
 * vector, so a caller holding a [previous] result cannot corrupt the buffer's state by editing
 * what it was handed.
 */
data class ObjectPosition(
	val x: Double,
	val y: Double,
	val z: Double,
)

/**
 * The previous-transform double buffer behind every dynamic world-pass velocity writer: a
 * per-frame snapshot of visible-object positions keyed by the object's stable id.
 *
 * Terrain velocity is a function of the camera alone and collapses into one reprojection per
 * frame; an entity's pixels are where the entity's *current* pose put them, so the velocity
 * writer also needs where each object sat in the previous frame. This class retains exactly
 * that: one committed frame of positions, one in-flight frame of captures, and nothing else.
 *
 * Two key domains share the buffer, disjoint by key type and storage: the entity domain - the
 * stable ids entity extraction assigns, positive ints, plus the static block-entity marker
 * semantics the block-entity writer keeps out of this buffer entirely - and the moving-block
 * domain - the packed long block-position identity ([MovingBlockVelocityWriterBindings.blockId])
 * the piston capture seam assigns. An int id and a long id that happen to carry the same
 * numeric value are different objects with independent history slots; the overloads resolve by
 * key type, so a moving-block id can never read an entity's predecessor and an entity id can
 * never read a block's. The int overloads are the entity writer's API and stay untouched.
 *
 * The lifecycle is the frame:
 *
 * 1. [capture] records each visible object's position for the frame being captured, before the
 *    world phase opens.
 * 2. [publish] is the frame boundary. Only here do captures become predecessors - [previous]
 *    and [displacement] are unaffected by mid-frame captures - and only here are ids absent
 *    from the frame evicted. Eviction matters beyond memory: Minecraft reuses entity ids, so
 *    without it a new entity taking over a despawned one's id would inherit the dead object's
 *    position and smear one frame of velocity.
 * 3. Between capture and publish the draw path reads [previous] (last committed frame) and
 *    [displacement] (this frame's capture minus it) for the objects it draws.
 *
 * No predecessor is returned on a first observation (nothing committed yet), after [reset], or
 * after eviction - every one of which must write the invalid sentinel rather than a fabricated
 * vector. [reset] forgets both frames and is required on any break in the DLSS chain (a vanilla
 * frame, a world change, an abandoned phase), exactly where the camera motion is reset; the
 * capture mixin wiring is a later slice's seam, this class only holds the contract.
 *
 * An id captured more than once in one frame keeps the last capture. The state is single
 * writer per frame by construction - one extractor pass - so no synchronization exists.
 */
class ObjectMotionState {
	/** Positions captured for the frame in flight, published at the next frame boundary. */
	private val captures = HashMap<Int, ObjectPosition>()

	/** The last [publish]ed frame's positions - the predecessors the draw path reads. */
	private val committed = HashMap<Int, ObjectPosition>()

	/**
	 * The moving-block domain's in-flight captures, keyed by the packed long block-position
	 * identity. Separate storage from the entity captures: the two domains can never share a
	 * history slot, no matter what numeric values the keys carry.
	 */
	private val blockCaptures = HashMap<Long, ObjectPosition>()

	/** The last [publish]ed moving-block frame's positions. */
	private val blockCommitted = HashMap<Long, ObjectPosition>()

	/**
	 * Records [id]'s position this frame. Does not become visible to [previous] until the next
	 * [publish].
	 */
	fun capture(id: Int, x: Double, y: Double, z: Double) {
		captures[id] = ObjectPosition(x, y, z)
	}

	/**
	 * Records one moving block's position this frame, in the block domain's own history.
	 * Does not become visible to [previous] until the next [publish].
	 */
	fun capture(id: Long, x: Double, y: Double, z: Double) {
		blockCaptures[id] = ObjectPosition(x, y, z)
	}

	/**
	 * The frame boundary: [capture]d positions become the predecessors of the next frame, and
	 * ids absent from this frame's captures are evicted from history, in both domains.
	 */
	fun publish() {
		committed.clear()
		committed.putAll(captures)
		captures.clear()
		blockCommitted.clear()
		blockCommitted.putAll(blockCaptures)
		blockCaptures.clear()
	}

	/**
	 * The position [id] was captured at in the last published frame, or null when there is no
	 * predecessor: a first observation, an id evicted at the boundary, or history [reset].
	 */
	fun previous(id: Int): ObjectPosition? = committed[id]

	/** The moving-block-domain predecessor of [id], from the last published frame. */
	fun previous(id: Long): ObjectPosition? = blockCommitted[id]

	/**
	 * This frame's captured position minus the last published one, for an object drawn at its
	 * captured position - the displacement the writer composes into the camera reprojection.
	 *
	 * Null when either half is missing: no current capture (the object is not alive this frame),
	 * or no predecessor (first observation, eviction, reset). A null displacement means the
	 * object's pixels must write the invalid sentinel, not a vector.
	 */
	fun displacement(id: Int): Vector3f? {
		val previous = committed[id] ?: return null
		val current = captures[id] ?: return null
		return Vector3f(
			(current.x - previous.x).toFloat(),
			(current.y - previous.y).toFloat(),
			(current.z - previous.z).toFloat(),
		)
	}

	/** The moving-block-domain displacement of [id], or null without a current capture and predecessor. */
	fun displacement(id: Long): Vector3f? {
		val previous = blockCommitted[id] ?: return null
		val current = blockCaptures[id] ?: return null
		return Vector3f(
			(current.x - previous.x).toFloat(),
			(current.y - previous.y).toFloat(),
			(current.z - previous.z).toFloat(),
		)
	}

	/**
	 * Forgets every predecessor and every in-flight capture, in both domains.
	 *
	 * Any break in the DLSS chain invalidates the history - a vanilla frame, a world or
	 * dimension change, a frame abandoned by an exception. The next observation of any id is a
	 * first observation again.
	 */
	fun reset() {
		captures.clear()
		committed.clear()
		blockCaptures.clear()
		blockCommitted.clear()
	}
}

/**
 * Composes one object's own motion into this frame's camera reprojection, producing the
 * per-object reprojection a dynamic world-pass velocity writer feeds the same fragment
 * reconstruction the terrain writer uses:
 *
 * ```
 * ndc = gl_FragCoord.xy inverted through the velocity viewport
 * clip = vec4(ndc, gl_FragCoord.z, 1.0)
 * motion = ndc(objectReprojection * clip) - ndc(clip)
 * ```
 *
 * The camera's published [DlssFrameMotion.reprojection] is the authoritative camera motion -
 * this function never rebuilds it. It wraps the object's own displacement around the current
 * jittered view-projection `Q = T(jitter) * currentViewProjection`:
 *
 * ```
 * objectReprojection = camera.reprojection * Q * T(-objectDelta) * inverse(Q)
 * ```
 *
 * `Q * T(-objectDelta) * inverse(Q)` conjugates the object's world displacement into the
 * camera-relative clip-space translation this frame's projection implies, so the composition
 * is mathematically the camera's `T(j) * previousViewProjection * T(cameraDelta) *
 * inverse(currentViewProjection) * T(-j)` with the object's camera-relative displacement
 * folded into the camera's:
 *
 * ```
 * = T(j) * previousViewProjection * T(cameraDelta - objectDelta)
 *       * inverse(currentViewProjection) * T(-j)
 * ```
 *
 * [objectDelta] is the object's *current minus previous* world displacement, the convention
 * [ObjectMotionState.displacement] derives. A surface point of the object at camera-relative
 * position `x` this frame sat at `x - objectDelta` last frame, which is what the conjugation
 * is shifting by. The invariants fall out: a world-still object (`objectDelta` zero) is
 * [camera.reprojection] itself - returned as a copy, so mutating the result cannot touch the
 * frame's matrix - and a translating object shifts its pixels by exactly its own
 * camera-relative NDC motion. Jitter is stripped and re-added exactly as the camera's
 * composition does, so it cancels against the untouched `ndc(clip)` term and a still frame
 * still reads zero.
 *
 * [camera] is the frame's published motion. A [DlssFrameMotion.reset] frame has no predecessor
 * worth pointing at, so the object reprojection is the identity - the same reset semantics the
 * camera reprojection already carries, and the velocity writer's reset flag forces the invalid
 * sentinel on top of it. An object with no predecessor of its own ([ObjectMotionState.previous]
 * or [ObjectMotionState.displacement] null) never reaches this function; the writer writes the
 * sentinel instead.
 *
 * Nothing here touches `clip.z` or `clip.w` beyond the transforms themselves, so reversed-Z
 * depth keeps meaning what it meant.
 */
fun objectReprojection(
	camera: DlssFrameMotion,
	currentViewProjection: Matrix4f,
	jitter: DlssJitterOffset,
	objectDelta: Vector3f,
): Matrix4f {
	if (camera.reset) {
		return Matrix4f()
	}
	if (objectDelta.x == 0f && objectDelta.y == 0f && objectDelta.z == 0f) {
		return Matrix4f(camera.reprojection)
	}
	// Q = T(jitter) * currentViewProjection, the frame's rendered - therefore jittered -
	// view-projection; the object's displacement is conjugated by it. JOML `mul` is
	// right-multiplication and `translate` post-multiplies, so the chain below is exactly
	// camera.reprojection * Q * T(-objectDelta) * inverse(Q).
	val jittered = Matrix4f()
		.translation(jitter.clipOffsetX, jitter.clipOffsetY, 0f)
		.mul(currentViewProjection)
	return Matrix4f(camera.reprojection)
		.mul(jittered)
		.translate(-objectDelta.x, -objectDelta.y, -objectDelta.z)
		.mul(Matrix4f(jittered).invert())
}
