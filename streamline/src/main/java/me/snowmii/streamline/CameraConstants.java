package me.snowmii.streamline;

import org.joml.Matrix4f;

import java.util.Arrays;
import java.util.Objects;

/**
 * One frame's real camera, in the flat ABI units {@code mc_dlss_evaluate} carries.
 *
 * <p>This is the whole non-optional half of Streamline's {@code sl::Constants}. {@code
 * sl_consts.h} opens with "all parameters must be provided unless they are marked as
 * optional", and every field it default-constructs holds {@code INVALID_FLOAT} (3.4e38) until
 * something writes it - so a field left out is not a field defaulted, it is {@code FLT_MAX}
 * handed to the plugin. DLSS SR survives that for the reprojection matrices because
 * {@code cameraMotionIncluded} sends it to the motion field instead; the DLSS-G plugin does
 * not, which is the upside-down world ghost seen on generated frames only while the rendered
 * frames stayed correct.
 *
 * <p>{@link #viewToClip} and {@link #clipToView} are 16 floats each in row-major order (the
 * layout {@code sl::float4x4} stores): the jitter-free view-to-clip projection the world
 * rendered with - view bob and portal/nausea skew included - and its inverse, both exactly as
 * the engine produced them. The temporal-AA jitter travels separately as
 * {@link EvaluationRequest#jitter()}.
 *
 * <p>{@link #clipToPrevClip} maps this frame's clip space to the previous frame's,
 * jitter-free, and {@link #prevClipToClip} is its inverse - the pair the DLSS-G plugin
 * interpolates the generated frame's camera through. {@link #near}, {@link #far},
 * {@link #fovRadians} (vertical), and {@link #aspectRatio} describe the same frustum the
 * projection does; the plugin reads them directly rather than re-deriving them.
 *
 * <p>{@link #pos} is the camera position in world space; {@link #right}, {@link #up}, and
 * {@link #fwd} are the camera's orthonormal world-space basis vectors (the directions of
 * view-space +X, +Y, and -Z, i.e. the direction the camera looks), extracted from the view
 * rotation. The plugin's auto scene-change detection verifies the basis is orthonormal before
 * it runs.
 */
public record CameraConstants(
	/** Row-major view-to-clip projection, 16 floats, jitter-free. */
	float[] viewToClip,
	/** Row-major clip-to-view inverse, 16 floats. */
	float[] clipToView,
	/** Camera position in world space, 3 floats. */
	float[] pos,
	/** World-space direction of view-space +X, 3 floats. */
	float[] right,
	/** World-space direction of view-space +Y, 3 floats. */
	float[] up,
	/** World-space direction of view-space -Z, where the camera looks, 3 floats. */
	float[] fwd,
	/**
	 * Row-major current-clip to previous-clip, 16 floats, jitter-free. Defaults to the
	 * identity - a still camera - for the callers that describe only where the camera is,
	 * never how it moved; the frame evaluation always supplies the real step.
	 */
	float[] clipToPrevClip,
	/** Row-major previous-clip to current-clip, 16 floats - the inverse of {@link #clipToPrevClip}. */
	float[] prevClipToClip,
	/** Near view-plane distance. */
	float near,
	/** Far view-plane distance. */
	float far,
	/** Vertical field of view, in radians. */
	float fovRadians,
	/** View-space width divided by height. */
	float aspectRatio,
	/**
	 * The pixel-space temporal-AA jitter offset the constants record carried, in render
	 * pixels. The SR oracle reports it raw; the FG oracle reports it with y negated, matching
	 * the FG viewport's y-flipped tags. Input cameras never carry it - the evaluation's jitter
	 * travels separately as {@link EvaluationRequest#jitter()}.
	 */
	float jitterX,
	float jitterY
) {
	public CameraConstants {
		Objects.requireNonNull(viewToClip, "viewToClip");
		Objects.requireNonNull(clipToView, "clipToView");
		Objects.requireNonNull(pos, "pos");
		Objects.requireNonNull(right, "right");
		Objects.requireNonNull(up, "up");
		Objects.requireNonNull(fwd, "fwd");
		Objects.requireNonNull(clipToPrevClip, "clipToPrevClip");
		Objects.requireNonNull(prevClipToClip, "prevClipToClip");
	}

	/**
	 * A full camera with zero jitter: the same shape the Kotlin default arguments produced
	 * for the callers that describe the step and frustum and carry no pixel jitter.
	 */
	public CameraConstants(
		float[] viewToClip,
		float[] clipToView,
		float[] pos,
		float[] right,
		float[] up,
		float[] fwd,
		float[] clipToPrevClip,
		float[] prevClipToClip,
		float near,
		float far,
		float fovRadians,
		float aspectRatio
	) {
		this(viewToClip, clipToView, pos, right, up, fwd, clipToPrevClip, prevClipToClip, near, far, fovRadians, aspectRatio, 0f, 0f);
	}

	/**
	 * A camera that reports where it is and how it points, never how it moved: a fresh
	 * identity clip-to-prev-clip pair (a still camera) and zeroed frustum scalars and jitter,
	 * matching the Kotlin defaults of the replaced data class.
	 */
	public CameraConstants(
		float[] viewToClip,
		float[] clipToView,
		float[] pos,
		float[] right,
		float[] up,
		float[] fwd
	) {
		this(
			viewToClip,
			clipToView,
			pos,
			right,
			up,
			fwd,
			identityMatrix(),
			identityMatrix(),
			0f,
			0f,
			0f,
			0f
		);
	}

	/**
	 * Converts one JOML matrix into the flat 16-float ABI layout {@code sl::float4x4} stores.
	 *
	 * <p>Do not transpose here. The two sides differ in <em>both</em> storage order and vector
	 * convention, and the two differences cancel exactly: JOML is column-vector stored
	 * column-major, Streamline is row-vector stored row-major, and both conventions put the
	 * same element at the same flat index. Transposing hands the plugin the transpose of the
	 * intended matrix: harmless for the identity (which is its own transpose) and fatal for a
	 * real perspective projection.
	 */
	public static float[] rowMajorOf(Matrix4f matrix) {
		return matrix.get(new float[16]);
	}

	private static float[] identityMatrix() {
		return new float[]{
			1f, 0f, 0f, 0f,
			0f, 1f, 0f, 0f,
			0f, 0f, 1f, 0f,
			0f, 0f, 0f, 1f,
		};
	}

	/**
	 * Compares the payload, not the array identities the generated {@code equals} would
	 * compare - every matrix and vector here is a {@code float[]}, so the generated
	 * implementation would answer false for two records holding the same camera in different
	 * arrays, which is exactly what a test comparing an oracle read against an expected
	 * record holds.
	 */
	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof CameraConstants camera)) {
			return false;
		}
		float[][] mine = arrays();
		float[][] theirs = camera.arrays();
		for (int i = 0; i < mine.length; i++) {
			if (!Arrays.equals(mine[i], theirs[i])) {
				return false;
			}
		}
		// Kotlin compares the scalar fields with numeric == (list equality on Float), so
		// -0.0f equals 0.0f and NaN never equals itself; boxed-Float Arrays.equals would
		// flip both. Keep the exact Kotlin semantics.
		float[] m = scalars();
		float[] t = camera.scalars();
		for (int i = 0; i < m.length; i++) {
			if (m[i] != t[i]) {
				return false;
			}
		}
		return true;
	}

	@Override
	public int hashCode() {
		int result = Arrays.hashCode(scalars());
		for (float[] array : arrays()) {
			result = 31 * result + Arrays.hashCode(array);
		}
		return result;
	}

	/** The array-valued fields in a fixed order, so equality and the hash read the same payload. */
	private float[][] arrays() {
		return new float[][]{viewToClip, clipToView, pos, right, up, fwd, clipToPrevClip, prevClipToClip};
	}

	/** The scalar fields, which compare and hash by value already. */
	private float[] scalars() {
		return new float[]{near, far, fovRadians, aspectRatio, jitterX, jitterY};
	}
}