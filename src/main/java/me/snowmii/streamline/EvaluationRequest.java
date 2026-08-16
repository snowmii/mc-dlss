package me.snowmii.streamline;

import java.util.Objects;

/**
 * One DLSS evaluation, in the units the flat native ABI takes them.
 *
 * <p>Only the engine's two images are carried. The motion and output images are the bridge's
 * own - allocated from the configured dimensions and reachable natively - so passing them back
 * would be handing the bridge handles it already holds.
 *
 * <p>{@link #renderDimensions} is not filled by whoever describes the frame. It is the size
 * the <em>native configuration</em> is on, and it is carried purely so the bridge can check
 * that its caller has not lost track of the configuration it asked for; the session's adapter
 * stamps it on the way through.
 *
 * <p>{@link #camera} is the frame's real camera, carried through the same call so the
 * evaluation's single {@code slSetConstants} records it together with the jitter, motion
 * scale, and reset flag under the frame's retained token. Null records a zero-filled camera:
 * an SR-only caller without a camera (a test double, or a frame whose camera was never
 * observed) still evaluates, and the module records whatever the struct carried.
 */
public record EvaluationRequest(
	/** The caller's shared Vulkan command buffer the evaluation is recorded on. */
	long commandBuffer,
	/** The engine's render-sized colour image. */
	ImageBinding color,
	/** The engine's render-sized depth image. */
	ImageBinding depth,
	/** Sub-pixel offset of this frame, in render pixels - the unit NGX takes it in. */
	Vec2 jitter,
	/** The scale that normalizes the motion buffer onto [-1, 1]. */
	Vec2 motionScale,
	/** This frame's duration, in milliseconds. */
	float frameTimeMilliseconds,
	/** Whether DLSS should discard its history and accumulate fresh. */
	boolean resetHistory,
	/** Stamped by the session adapter; see the class comment. */
	Dimensions renderDimensions,
	/** The frame's real camera, or null to record a zero-filled camera. */
	CameraConstants camera
) {
	public EvaluationRequest {
		Objects.requireNonNull(color, "color");
		Objects.requireNonNull(depth, "depth");
		Objects.requireNonNull(jitter, "jitter");
		Objects.requireNonNull(motionScale, "motionScale");
	}

	/**
	 * An all-defaults request, mirroring the Kotlin constructor this type replaced:
	 * zeroed command buffer and images, zero jitter and motion scale, no reset, no
	 * dimensions, no camera.
	 */
	public EvaluationRequest() {
		this(0L, new ImageBinding(0, 0, 0), new ImageBinding(0, 0, 0), new Vec2(0f, 0f), new Vec2(0f, 0f), 0f, false, null, null);
	}

	/**
	 * Fluent constructor for a nine-field request whose adjacent same-typed pairs would
	 * otherwise be transposed by either side without any diagnostic - the reason the Kotlin
	 * contract named its arguments, which Java constructors cannot.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/** One method per field, naming each argument; {@link #build()} validates like the ctor. */
	public static final class Builder {
		private long commandBuffer;
		private ImageBinding color = new ImageBinding(0, 0, 0);
		private ImageBinding depth = new ImageBinding(0, 0, 0);
		private Vec2 jitter = new Vec2(0f, 0f);
		private Vec2 motionScale = new Vec2(0f, 0f);
		private float frameTimeMilliseconds;
		private boolean resetHistory;
		private Dimensions renderDimensions;
		private CameraConstants camera;

		private Builder() {
		}

		public Builder commandBuffer(long commandBuffer) {
			this.commandBuffer = commandBuffer;
			return this;
		}

		public Builder color(ImageBinding color) {
			this.color = color;
			return this;
		}

		public Builder depth(ImageBinding depth) {
			this.depth = depth;
			return this;
		}

		public Builder jitter(Vec2 jitter) {
			this.jitter = jitter;
			return this;
		}

		public Builder motionScale(Vec2 motionScale) {
			this.motionScale = motionScale;
			return this;
		}

		public Builder frameTimeMilliseconds(float frameTimeMilliseconds) {
			this.frameTimeMilliseconds = frameTimeMilliseconds;
			return this;
		}

		public Builder resetHistory(boolean resetHistory) {
			this.resetHistory = resetHistory;
			return this;
		}

		public Builder renderDimensions(Dimensions renderDimensions) {
			this.renderDimensions = renderDimensions;
			return this;
		}

		public Builder camera(CameraConstants camera) {
			this.camera = camera;
			return this;
		}

		public EvaluationRequest build() {
			return new EvaluationRequest(
				commandBuffer,
				color,
				depth,
				jitter,
				motionScale,
				frameTimeMilliseconds,
				resetHistory,
				renderDimensions,
				camera
			);
		}
	}
}