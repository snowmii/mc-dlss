package me.snowmii.dlss.bridge

import org.joml.Matrix4f

/**
 * Converts one JOML matrix into the flat row-major 16-float ABI layout `sl::float4x4` stores.
 *
 * JOML keeps matrices column-major and `Matrix4f.get(float[])` writes the array column-major,
 * while the ABI (and Streamline's `sl::Constants`) is row-major, so the payload is transposed
 * here - the one place the layout conversion happens, keeping the native side's verbatim copy
 * honest. `rowMajor[r * 4 + c]` is the element at matrix row `r`, column `c`.
 */
fun rowMajorOf(matrix: Matrix4f): FloatArray {
	val columnMajor = FloatArray(16)
	matrix.get(columnMajor)
	val rowMajor = FloatArray(16)
	for (column in 0 until 4) {
		for (row in 0 until 4) {
			rowMajor[row * 4 + column] = columnMajor[column * 4 + row]
		}
	}
	return rowMajor
}

/**
 * One frame's real camera, in the flat ABI units `mc_dlss_evaluate` carries.
 *
 * [viewToClip] and [clipToView] are 16 floats each in row-major order (the layout
 * `sl::float4x4` stores), matching the projection the world actually rendered with - view
 * bob and portal/nausea skew included - minus the temporal-AA jitter, which travels
 * separately as [EvaluationRequest.jitter]. [pos] is the camera position in world space;
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
	/** Camera position in world space. */
	val pos: FloatArray,
	/** World-space direction of view-space +X. */
	val right: FloatArray,
	/** World-space direction of view-space +Y. */
	val up: FloatArray,
	/** World-space direction of view-space -Z, where the camera looks. */
	val fwd: FloatArray,
)
