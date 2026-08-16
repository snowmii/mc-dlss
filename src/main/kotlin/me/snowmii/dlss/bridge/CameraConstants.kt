package me.snowmii.dlss.bridge

import org.joml.Matrix4f

/**
 * Converts one JOML matrix into the flat 16-float ABI layout `sl::float4x4` stores.
 *
 * DO NOT transpose here. The two sides differ in *both* storage order and vector convention,
 * and the two differences cancel exactly:
 *
 * - JOML is column-vector (`v' = M * v`) stored column-major: `Matrix4f.get(float[])` writes
 *   `array[c * 4 + r]` for the element at row `r`, column `c`, so a translation sits at
 *   indices 12/13/14 and a perspective matrix's `w = -z` term at index 11.
 * - Streamline is row-vector (`v' = v * M`) stored row-major. `sl_matrix_helpers.h` proves the
 *   convention: `recalculateCameraMatrices` builds `cameraViewToWorld` with the basis in
 *   `row[0..2]` and `cameraPos` in `row[3]` - indices 12/13/14 - and `matrixMul` chains
 *   left-to-right (`clipToCameraView * viewToPrevView * viewToClipPrev`).
 *
 * Both conventions therefore put the same element at the same flat index, and JOML's array is
 * already the payload Streamline expects. Transposing it hands the plugin the transpose of the
 * intended matrix: harmless for the identity (which is its own transpose) and fatal for a real
 * perspective projection, whose `w` term lands at index 14 and whose derived near/far/FOV come
 * back as garbage.
 */
fun rowMajorOf(matrix: Matrix4f): FloatArray = matrix.get(FloatArray(16))

/**
 * One frame's real camera, in the flat ABI units `mc_dlss_evaluate` carries.
 *
 * This is the whole non-optional half of Streamline's `sl::Constants`. `sl_consts.h` opens with
 * "all parameters must be provided unless they are marked as optional", and every field it
 * default-constructs holds `INVALID_FLOAT` (`3.4e38`) until something writes it - so a field
 * left out is not a field defaulted, it is `FLT_MAX` handed to the plugin. DLSS SR survives
 * that for the reprojection matrices because `cameraMotionIncluded` sends it to the motion
 * field instead; the DLSS-G plugin does not, which is the upside-down world ghost seen on
 * generated frames only while the rendered frames stayed correct.
 *
 * [viewToClip] and [clipToView] are 16 floats each in row-major order (the layout
 * `sl::float4x4` stores): the jitter-free view-to-clip projection the world rendered with -
 * view bob and portal/nausea skew included - and its inverse, both exactly as the engine
 * produced them. The temporal-AA jitter travels separately as [EvaluationRequest.jitter].
 *
 * [clipToPrevClip] maps this frame's clip space to the previous frame's, jitter-free, and
 * [prevClipToClip] is its inverse - the pair the DLSS-G plugin interpolates the generated
 * frame's camera through. [near], [far], [fovRadians] (vertical), and [aspectRatio] describe
 * the same frustum the projection does; the plugin reads them directly rather than
 * re-deriving them.
 *
 * A composed frame's evaluation records the same camera twice: once on the SR viewport for
 * the DLSS SR evaluation, and once on the FG-only viewport under the same frame token, for
 * the DLSS-G plugin, which reads per-frame constants from the viewport its options, state,
 * and tags were recorded on. The FG record carries the FG viewport's orientation: the four
 * clip-space matrices conjugate with F = diag(1, -1, 1, 1) and the jitter's y negates,
 * matching the y-flipped images the FG tag names, while the SR record stays raw.
 * `NativeApi.queryFgCameraConstants` is the FG record's oracle;
 * `NativeApi.queryCameraConstants` is the SR record's - the two oracles report exactly what
 * each viewport received, flipped jitter included, which is how the orientation split stays
 * provable.
 *
 * [pos] is the camera position in world space; [right], [up], and [fwd] are the camera's
 * orthonormal world-space basis vectors (the directions of view-space +X, +Y, and -Z, i.e.
 * the direction the camera looks), extracted from the view rotation. The plugin's auto
 * scene-change detection verifies the basis is orthonormal before it runs.
 */
data class CameraConstants(
	/** Row-major view-to-clip projection, 16 floats, jitter-free. */
	val viewToClip: FloatArray,
	/** Row-major clip-to-view inverse, 16 floats. */
	val clipToView: FloatArray,
	/** Camera position in world space, 3 floats. */
	val pos: FloatArray,
	/** World-space direction of view-space +X, 3 floats. */
	val right: FloatArray,
	/** World-space direction of view-space +Y, 3 floats. */
	val up: FloatArray,
	/** World-space direction of view-space -Z, where the camera looks, 3 floats. */
	val fwd: FloatArray,
	/**
	 * Row-major current-clip to previous-clip, 16 floats, jitter-free. Defaults to the
	 * identity - a still camera - for the callers that describe only where the camera is,
	 * never how it moved; the frame evaluation always supplies the real step.
	 */
	val clipToPrevClip: FloatArray = IDENTITY_MATRIX,
	/** Row-major previous-clip to current-clip, 16 floats - the inverse of [clipToPrevClip]. */
	val prevClipToClip: FloatArray = IDENTITY_MATRIX,
	/** Near view-plane distance. */
	val near: Float = 0f,
	/** Far view-plane distance. */
	val far: Float = 0f,
	/** Vertical field of view, in radians. */
	val fovRadians: Float = 0f,
	/** View-space width divided by height. */
	val aspectRatio: Float = 0f,
	/**
	 * The pixel-space temporal-AA jitter offset the constants record carried, in render
	 * pixels. The SR oracle reports it raw; the FG oracle reports it with y negated,
	 * matching the FG viewport's y-flipped tags. Input cameras never carry it - the
	 * evaluation's jitter travels separately as [EvaluationRequest.jitter].
	 */
	val jitterX: Float = 0f,
	val jitterY: Float = 0f,
) {
	/**
	 * Compares the payload, not the array identities the generated `equals` would compare.
	 *
	 * Every matrix and vector here is a `FloatArray`, so the generated implementation answers
	 * false for two records holding the same camera in different arrays - which is exactly what
	 * a test comparing an oracle read against an expected record holds.
	 */
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is CameraConstants) return false
		val sameArrays = arrays().zip(other.arrays()).all { (mine, theirs) -> mine.contentEquals(theirs) }
		return sameArrays && scalars() == other.scalars()
	}

	override fun hashCode(): Int =
		arrays().fold(scalars().hashCode()) { result, array -> 31 * result + array.contentHashCode() }

	/** The array-valued fields in a fixed order, so equality and the hash read the same payload. */
	private fun arrays(): List<FloatArray> =
		listOf(viewToClip, clipToView, pos, right, up, fwd, clipToPrevClip, prevClipToClip)

	/** The scalar fields, which compare and hash by value already. */
	private fun scalars(): List<Float> = listOf(near, far, fovRadians, aspectRatio, jitterX, jitterY)
}

/** The row-major identity, the default camera step of a caller that reports no motion. */
private val IDENTITY_MATRIX: FloatArray
	get() = floatArrayOf(
		1f, 0f, 0f, 0f,
		0f, 1f, 0f, 0f,
		0f, 0f, 1f, 0f,
		0f, 0f, 0f, 1f,
	)
