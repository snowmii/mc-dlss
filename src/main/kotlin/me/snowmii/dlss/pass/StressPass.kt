package me.snowmii.dlss.pass
import me.snowmii.dlss.config.ModConfig
import me.snowmii.dlss.mrt.VelocityContext
import me.snowmii.dlss.mrt.velocityTwin
import me.snowmii.dlss.render.DlssCameraSample

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.buffers.Std140SizeCalculator
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import org.joml.Matrix4f
import org.joml.Vector4f
import org.lwjgl.system.MemoryStack
import java.util.Optional
import java.util.Properties

/**
 * How much work the stress pass asks the GPU for, and whether it runs at all.
 *
 * A value derived from the mod's one config handle, [ModConfig], rather than from
 * [me.snowmii.dlss.session.DlssStartupConfig]: this is a measurement instrument, not part of the
 * DLSS contract, and nothing downstream of the startup configuration should have to know it
 * exists. Its knobs are still named and parsed in [ModConfig] with every other `-Dmc.dlss.*`
 * property; the instrument reads them off the handle itself.
 */
data class StressConfig(
	val enabled: Boolean,
	/** Primary raymarch steps per pixel. The dominant cost. */
	val steps: Int,
	/** FBM octaves per density sample. Multiplies the cost of every step. */
	val octaves: Int,
	/** Radial godray taps. Screen-space, so its cost is independent of the march. */
	val godrayTaps: Int,
	/** Scales the effect's contribution; 0 renders the scene unchanged but still pays for it. */
	val intensity: Float,
	/**
	 * Sign applied to the reconstructed NDC y.
	 *
	 * Which one is right depends on whether the backend's viewport is y-flipped relative to the
	 * projection the world was rendered with, and the only way to be sure is to look at a frame:
	 * a wrong sign puts the aurora band under the terrain instead of above it. Flipping it is one
	 * property rather than a rebuild.
	 */
	val ndcYSign: Float,
) {
	companion object {
		fun from(properties: Properties = System.getProperties()): StressConfig = from(ModConfig.from(properties))

		private fun from(handle: ModConfig): StressConfig = StressConfig(
			enabled = handle.stressEnabled,
			steps = handle.stressSteps,
			octaves = handle.stressOctaves,
			godrayTaps = handle.stressGodrayTaps,
			intensity = handle.stressIntensity,
			ndcYSign = handle.stressNdcYSign,
		)
	}
}

/**
 * A deliberately expensive full-screen effect drawn over the world scene, inside the world phase.
 *
 * This exists to make DLSS measurable. A vanilla Minecraft frame is usually CPU- or
 * draw-call-bound, so halving the render resolution moves the frame rate very little and an
 * upscaler looks like it does nothing. This pass adds a large, purely fragment-bound cost that
 * scales with the *render* resolution, which is the regime DLSS is built for: the same scene, the
 * same output resolution, and a workload that gets cheaper exactly in proportion to how much of
 * the frame DLSS is reconstructing rather than rendering.
 *
 * It is deliberately placed at the tail of the world phase, before evaluation:
 *
 *  - on a DLSS frame it runs against the low-resolution scene target, so DLSS upscales the
 *    finished, effect-included image and the cost is paid at render resolution;
 *  - on a vanilla frame it runs against the main target at output resolution, which is the
 *    comparison the measurement needs.
 *
 * Everything outside the world phase is untouched. The pass reads a target's colour and depth,
 * writes to its own scratch target, and blits back, so no vanilla target is resized, no vanilla
 * pipeline is replaced, and no vanilla render state is left changed. Turning it off - the
 * default - leaves the frame byte-identical to a build without it.
 */
class StressPass(
	private val config: StressConfig,
	private val diagnostics: (String) -> Unit = {},
) : ScenePass {
	private var scratchTarget: RenderTarget? = null
	private var stressUniformBuffer: GpuBuffer? = null
	private var startedAtNanos = 0L
	private var disabledAfterFailure = false

	private val viewProjection = Matrix4f()
	private val inverseViewProjection = Matrix4f()
	private val sunClip = Vector4f()

	/** Identity reprojection for frames with no published motion: the shader then writes the sentinel, never this matrix's zero. */
	private val identityReprojection = Matrix4f()

	/** Runtime switch, so one session can compare loaded and unloaded frames. */
	override var enabled: Boolean = config.enabled
		private set

	/** Flips the effect on or off and returns the state now in effect. */
	override fun toggle(): Boolean {
		enabled = !enabled && !disabledAfterFailure
		return enabled
	}

	/** One line naming the workload, for the same readout the DLSS controls print. */
	override fun readout(): String = when {
		disabledAfterFailure -> "stress off (shader failed)"
		!enabled -> "stress off"
		else -> "stress on | ${config.steps} steps | ${config.octaves} octaves | ${config.godrayTaps} godray taps"
	}

	/**
	 * Draws the effect over [target], in place.
	 *
	 * [camera] supplies the frame's unjittered world projection and view rotation, which is what
	 * the shader reconstructs world positions with. A frame without one is skipped rather than
	 * rendered with a stale camera: a one-frame-old ray basis would swim visibly and would make
	 * the measurement noisier, not just uglier.
	 *
	 * [velocity] is this frame's velocity-MRT write context when the open world phase offers its
	 * scene-sized RG16_FLOAT velocity view on the velocity route, or null on a vanilla or
	 * camera-only frame. With a context the pass binds a two-target twin of its own pipeline and
	 * writes jitter-free NDC camera motion into the view at color index 1, alongside the effect
	 * in the scratch target; without one it keeps the exact one-target pass. The shader derives
	 * the vectors from the published reprojection and the reversed-Z depth, so the pass writes
	 * the same camera-motion payload DLSS expects of the scene it is measuring.
	 *
	 * A failure disables the pass for the rest of the session. This is an instrument bolted onto
	 * the render thread; it must never be the reason a frame does not finish.
	 */
	override fun render(target: RenderTarget, camera: DlssCameraSample, velocity: VelocityContext?) {
		if (!enabled || disabledAfterFailure) {
			return
		}

		val color = target.colorTextureView ?: return
		val depth = target.depthTextureView ?: return
		if (target.width <= 0 || target.height <= 0) {
			return
		}

		try {
			val scratchTarget = scratchFor(target.width, target.height)
			val scratchColor = scratchTarget.colorTextureView ?: return
			val uniforms = writeStressUniforms(camera, target.width, target.height, velocity)
			val samplers = RenderSystem.getSamplerCache()
			val encoder = RenderSystem.getDevice().createCommandEncoder()

			// The one-target shape on vanilla and camera-only frames; the two-attachment shape -
			// scratch colour at 0, the scene's RG16_FLOAT velocity view at 1 - on an open
			// velocity-MRT route. The pipeline bound must carry the same target count and format
			// as the attachments, or setPipeline throws on the first bind.
			val pass = if (velocity == null) {
				encoder.createRenderPass({ "DLSS stress" }, scratchColor, Optional.empty())
			} else {
				encoder.createRenderPass(
					RenderPassDescriptor.create { "DLSS stress velocity" }
						.withColorAttachment(scratchColor, Optional.empty())
						.withColorAttachment(velocity.view, Optional.empty())
						.withRenderArea(RenderPass.RenderArea(0, 0, target.width, target.height)),
				)
			}
			pass.use { renderPass ->
				renderPass.setPipeline(pipelineForVelocityRoute(velocity))
				renderPass.setUniform("StressConfig", uniforms)
				renderPass.bindTexture("InSampler", color, samplers.getClampToEdge(FilterMode.LINEAR))
				renderPass.bindTexture("InDepthSampler", depth, samplers.getClampToEdge(FilterMode.NEAREST))
				renderPass.draw(3, 1, 0, 0)
			}

			encoder.createRenderPass({ "DLSS stress resolve" }, color, Optional.empty()).use { pass ->
				RenderSystem.bindDefaultUniforms(pass)
				pass.setPipeline(RenderPipelines.TRACY_BLIT)
				pass.bindTexture("InSampler", scratchColor, samplers.getClampToEdge(FilterMode.NEAREST))
				pass.draw(3, 1, 0, 0)
			}
		} catch (failure: Throwable) {
			disabledAfterFailure = true
			enabled = false
			releaseScratch()
			diagnostics("DLSS stress pass disabled after failure: $failure")
		}
	}

	override fun close() {
		releaseScratch()
		stressUniformBuffer?.close()
		stressUniformBuffer = null
	}

	private fun scratchFor(width: Int, height: Int): RenderTarget {
		val existing = scratchTarget
		if (existing != null && existing.width == width && existing.height == height) {
			return existing
		}

		releaseScratch()
		// No depth: the pass reads the world's depth as a texture and writes colour only.
		return TextureTarget(SCRATCH_LABEL, width, height, false, GpuFormat.RGBA8_UNORM).also { scratchTarget = it }
	}

	private fun releaseScratch() {
		scratchTarget?.destroyBuffers()
		scratchTarget = null
	}

	/**
	 * Fills this frame's uniform block and returns its buffer binding.
	 *
	 * The sun is a fixed world direction rather than the world's own sun: the effect is a
	 * measurement load, and a light that moves with the day cycle would make two runs of the same
	 * benchmark cost visibly different amounts.
	 */
	private fun writeStressUniforms(
		camera: DlssCameraSample,
		width: Int,
		height: Int,
		velocity: VelocityContext?,
	): GpuBufferSlice {
		val now = System.nanoTime()
		if (startedAtNanos == 0L) {
			startedAtNanos = now
		}
		val seconds = (now - startedAtNanos) / 1_000_000_000.0f

		viewProjection.set(camera.projection).mul(camera.viewRotation)
		inverseViewProjection.set(viewProjection).invert()

		// Sun position in UV space, for the screen-space godrays. A sun behind the camera has no
		// screen position, and the shader skips the gather rather than smearing from a wrapped one.
		sunClip.set(SUN_X, SUN_Y, SUN_Z, 0.0f)
		viewProjection.transform(sunClip)
		var sunU = -1.0f
		var sunV = -1.0f
		if (sunClip.w > 1.0e-4f) {
			sunU = (sunClip.x / sunClip.w) * 0.5f + 0.5f
			sunV = (sunClip.y / sunClip.w) * config.ndcYSign * 0.5f + 0.5f
		}

		// This frame's camera motion: the reprojection the shader derives velocity from, and
		// whether the frame has no valid predecessor (which forces the invalid sentinel).
		val reprojection = velocity?.reprojection ?: identityReprojection
		val invalid = velocity?.reset == true

		val buffer = stressUniformBuffer ?: RenderSystem.getDevice()
			.createBuffer({ "DLSS Stress Config" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST, UBO_SIZE.toLong())
			.also { stressUniformBuffer = it }

		MemoryStack.stackPush().use { stack ->
			val data = Std140Builder.onStack(stack, UBO_SIZE)
				.putMat4f(inverseViewProjection)
				.putVec4(camera.cameraX.toFloat(), camera.cameraY.toFloat(), camera.cameraZ.toFloat(), seconds)
				.putVec4(SUN_X, SUN_Y, SUN_Z, config.intensity)
				.putVec4(config.steps.toFloat(), config.octaves.toFloat(), config.ndcYSign, config.godrayTaps.toFloat())
				.putVec4(width.toFloat(), height.toFloat(), sunU, sunV)
				.putMat4f(reprojection)
				.putVec4(if (invalid) 1f else 0f, 0f, 0f, 0f)
				.get()
			RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), data)
		}

		return buffer.slice()
	}

	companion object {
		private const val SCRATCH_LABEL = "DLSS Stress"

		/** Fixed world-space sun direction; see [writeStressUniforms]. */
		private const val SUN_X = 0.34f
		private const val SUN_Y = 0.52f
		private const val SUN_Z = -0.78f

		private val UBO_SIZE = Std140SizeCalculator()
			.putMat4f()
			.putVec4()
			.putVec4()
			.putVec4()
			.putVec4()
			.putMat4f()
			.putVec4()
			.get()

		private val STRESS_LAYOUT: BindGroupLayout = BindGroupLayout.builder()
			.withUniform("StressConfig", UniformType.UNIFORM_BUFFER)
			.withSampler("InSampler")
			.withSampler("InDepthSampler")
			.build()

		/**
		 * Compiled lazily on first use, from the mod's own resource pack.
		 *
		 * Not registered with [RenderPipelines]: registration is private to Minecraft and only
		 * buys eager precompilation at resource reload. A first frame that pays the compile is
		 * cheaper than the alternative, and a broken shader disables the pass rather than
		 * failing the client's shader load.
		 */
		private val STRESS_PIPELINE: RenderPipeline = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("mc-dlss", "pipeline/dlss_stress"))
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath("mc-dlss", "post/dlss_stress"))
			.withBindGroupLayout(STRESS_LAYOUT)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build()

		/**
		 * The pipeline the pass binds for one frame, selected from this frame's velocity context.
		 *
		 * A null context - vanilla and camera-only routes - keeps the one-target [STRESS_PIPELINE]
		 * untouched: that pipeline is the identity of the comparison the stress pass exists to
		 * make, so it is never mutated or rebuilt. A velocity context selects the cached two-target
		 * twin, whose second target is the unblended RG16_FLOAT velocity payload the pass's second
		 * attachment carries. The twin preserves this pass's shaders and bind-group layout, so one
		 * shader source compiles for both shapes and the extra fragment output is simply discarded
		 * on the one-target route.
		 */
		internal fun pipelineForVelocityRoute(velocity: VelocityContext?): RenderPipeline =
			if (velocity == null) STRESS_PIPELINE else velocityTwin(STRESS_PIPELINE)

		/** Production wiring: the configuration this session started with. */
		@JvmStatic
		fun forMinecraft(diagnostics: (String) -> Unit): StressPass =
			StressPass(StressConfig.from(), diagnostics)
	}
}
