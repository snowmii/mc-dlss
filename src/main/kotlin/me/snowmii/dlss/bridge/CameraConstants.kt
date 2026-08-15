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
 * [viewToClip] and [clipToView] are 16 floats each in row-major order (the layout
 * `sl::float4x4` stores), already expressed in the image-space Y convention the DLSS-G
 * plugin reads, not the raw engine projection: [viewToClip] is the jitter-free view-to-clip
 * projection the world rendered with - view bob and portal/nausea skew included - with its
 * Y column negated, and [clipToView] is the matching inverse with its Y row negated, so the
 * pair still round-trips. The temporal-AA jitter travels separately as
 * [EvaluationRequest.jitter]. [pos] is the camera position in world space;
 * [right], [up], and [fwd] are the camera's orthonormal world-space basis vectors (the
 * directions of view-space +X, +Y, and -Z, i.e. the direction the camera looks), extracted
 * from the view rotation. The DLSS-G plugin interpolates the generated frame's camera from
 * these, and its auto scene-change detection verifies the basis is orthonormal before it
 * runs.
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
)
