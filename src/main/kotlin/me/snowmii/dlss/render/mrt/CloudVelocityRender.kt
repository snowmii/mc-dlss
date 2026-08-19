package me.snowmii.dlss.render.mrt

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.buffers.Std140SizeCalculator
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.GpuDevice
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import me.snowmii.dlss.client.ClientRuntime
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitterOffset
import me.snowmii.dlss.render.WorldPhase
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4fc
import org.lwjgl.system.MemoryStack
import org.slf4j.LoggerFactory
import java.util.Optional
import java.util.OptionalDouble
import java.util.function.Supplier
import kotlin.math.abs

/**
 * The cloud pass velocity writer: the control seam, the per-frame cloud-offset state, the
 * drift-composed CloudVelocityConfig payload write, the failure-atomic pass-creation
 * interception, and the writer twin's shader and layout surface.
 *
 * `CloudRenderer.render` is the last bespoke world pass before the protected hand seam: it
 * creates one pass over the clouds target (or the main target without the transparency chain)
 * with `RenderPipelines.CLOUDS` or `RenderPipelines.FLAT_CLOUDS` and draws the CPU-baked cloud
 * cells through the `CloudFaces` texel buffer with the `CloudInfo`/`DynamicTransforms`
 * uniforms and a single QUADS index draw. The pass-creation redirect asks this object whether
 * the open world phase offers the scene velocity view; when it does, [createCloudVelocityPass] builds a
	 * two-attachment pass - the source cloud color target at index 0 unchanged, the scene-sized
	 * RG16_FLOAT velocity view at index 1 - and the pipeline-boundary seam [bindCloudPipeline] binds
	 * the cached cloud writer twin, whose fragment shader ([FRAGMENT_SHADER], swapped in for the
	 * source's core/rendertype_clouds shader by [writerTwin] for [VelocityWriter.CLOUD])
	 * reproduces the vanilla cloud color output (the vertex color with only the fog alpha fade)
	 * and writes NDC motion into the velocity attachment. Fabulous clouds use a separate target
	 * whose depth does not hold the terrain, so that pass depth-tests against the scene depth
	 * with writes off ([cloudSceneDepthTwin]) and does not leak cloud motion through nearer
	 * geometry. The
 * `CloudInfo`/`CloudFaces`/`DynamicTransforms` binds, the depth behavior, and the draw count
 * are the source render call's own - the twin carries the source layouts so those binds
 * resolve exactly as before, and the redirects never touch the draw.
 *
 * ## Failure-atomic interception
 *
 * The cloud pass is created inline on the shared command encoder and then handed to the
 * source render call, which continues to bind uniforms, draw, and close it. A two-attachment
 * pass can never fall back to the source one-target pipeline (`RenderPass.setPipeline`
 * rejects an attachment-count mismatch), so no failure may be allowed to strike after the
 * MRT pass exists. The interception is therefore split into a preflight ([preflightCloudPass]) that
 * performs every fallible writer operation before the pass is constructed, and an owned
 * post-creation region ([createCloudVelocityPass]'s guarded body, [bindCloudPipeline], [closeCloudVelocityPass]) whose
 * operations cannot fail after a successful preflight:
 *
 * - The cloud clock read, the payload computation, the payload-buffer allocation, the twin
 *   construction, and the twin's lazy shader compilation (surfaced through
 *   `GpuDevice.precompilePipeline`, which is exactly the compile the first bind would
 *   trigger) all run inside [preflightCloudPass]'s guarded region. Any failure there - or an attachment
 *   input the pass constructor would reject - answers false, and [createCloudVelocityPass] falls through
 *   to the exact vanilla one-attachment creation with the source pipeline binding unchanged.
 * - The MRT pass creation and the CloudVelocityConfig uniform bind are one guarded region:
 *   a failure closes the partial pass (if one was created) so the shared encoder can host
 *   the vanilla pass, then falls through to the exact vanilla creation. The realistic
 *   validation failures the pass constructor raises happen before the encoder enters the
 *   pass, so the vanilla fallback always succeeds there; a backend-level failure that
 *   corrupts the shared encoder is a device catastrophe even vanilla's own pass could not
 *   recover from, and the fallback's own exception is the loud end of that frame.
 * - [bindCloudPipeline]'s twin path is no-throw by construction after a successful preflight:
 *   the twin is the preflighted cache hit (already compiled and validated), the uniform
 *   binding uses the pre-allocated buffer at offset zero (always alignment-valid), and the
 *   pass attachment count and formats match the twin by construction. The preflight is the
 *   guard; there is no recoverable failure at the bind.
 * - [closeCloudVelocityPass] is the owned close seam: the source render's try-with-resources closes the
 *   writer's pass, so a device-level close failure is absorbed and logged instead of
 *   throwing, and the pass latch is dropped. The writer never closes a pass it did not
 *   create and never double-closes.
 *
 * Ineligible routes - a closed phase, a vanilla session, the latched camera-only route, or a
 * frame whose scene target carries no velocity companion - answer false from the gates and
 * null from `velocityView`: the pass-creation redirect falls through to the exact vanilla
 * one-attachment creation and the source cloud pipeline binds unchanged. Every read here is
 * a plain field or enum read, and every device call sits behind a guarded seam, so the
 * fallback path cannot throw.
 */
object CloudVelocityRender {
	/** The shader path the cloud twin swaps in for the source's core/rendertype_clouds shader. */
	const val SHADER_PATH = "core/velocity_clouds"

	/** The payload uniform name, which must match the shader block name exactly. */
	const val UNIFORM_NAME = "CloudVelocityConfig"

	/**
	 * The constant cloud pattern drift in blocks per tick of the game clock, mirroring the
	 * render call's `cloudOffset * 0.030000001F`. The pattern anchor sits at
	 * `cell * 12 - cloudOffset * 0.03`, so the surface moves toward -X as the clock advances.
	 */
	const val DRIFT_BLOCKS_PER_TICK = 0.03f

	/**
	 * The clock jump that counts as a discontinuity rather than a frame's advance: a world
	 * change restarts the game clock at zero (a jump of thousands of ticks), while a rendered
	 * frame's advance is around one tick (a frame-time bound generous enough to keep even
	 * single-digit FPS below it). A jump beyond this bound writes the sentinel instead of a
	 * fabricated drift.
	 */
	const val MAX_CLOCK_JUMP_TICKS = 8.0f

	/** The cloud twin adds its own CloudVelocityConfig layout, distinct from every other writer's. */
	@JvmField
	val LAYOUT: BindGroupLayout = BindGroupLayout.builder()
		.withUniform(UNIFORM_NAME, UniformType.UNIFORM_BUFFER)
		.build()

	@JvmField
	val FRAGMENT_SHADER: Identifier = Identifier.fromNamespaceAndPath("mc-dlss", SHADER_PATH)

	/** `mat4 ObjectReprojection` + `vec4 VelocityParams`, both std140-aligned. */
	@JvmField
	val UBO_SIZE: Int = Std140SizeCalculator()
		.putMat4f()
		.putVec4()
		.get()

	private val LOGGER = LoggerFactory.getLogger("me.snowmii.dlss.mrt.CloudVelocityRender")

	private var uniformBuffer: GpuBuffer? = null

	/**
	 * The previous rendered frame's cloud clock - the writer's entire per-frame state.
	 *
	 * Null before the first observed cloud render call, which is exactly the no-predecessor
	 * sentinel case.
	 */
	private var previousClock: CloudClock? = null

	/**
	 * The cloud pass latch: the two-attachment pass this frame's render call is drawing into.
	 *
	 * Set by [createCloudVelocityPass] only when the MRT pass was fully set up, cleared by [closeCloudVelocityPass],
	 * and consulted by [bindCloudPipeline]. A stale latch is dropped at the head of every
	 * [createCloudVelocityPass], so a render that never closed its pass (a crashed frame) can never
	 * misattribute a later render's pass.
	 */
	private val CLOUD_VELOCITY_PASS = ThreadLocal<RenderPass>()

	/**
	 * True when this frame's latched cloud MRT pass borrowed the scene depth. Fabulous clouds
	 * otherwise depth-test against a cleared clouds-target depth and write motion through the
	 * terrain in front of them.
	 */
	private val CLOUD_SCENE_DEPTH_TEST = ThreadLocal.withInitial { false }

	/** The two cloud pipelines the render call can bind, exactly the twins [preflightCloudPass] preflights. */
	private val CLOUD_PIPELINES = listOf(RenderPipelines.CLOUDS, RenderPipelines.FLAT_CLOUDS)

	/**
	 * The twins [preflightCloudPass] preflighted this frame, keyed by the source cloud pipeline. Only a
	 * pipeline in this map may take the twin path in [bindCloudPipeline]; anything else (unreachable
	 * in the mapped render call) binds the source pipeline and fails loudly.
	 */
	private val preflightedTwins = HashMap<RenderPipeline, RenderPipeline>()

	/**
	 * Headless test seam: the open phase the pass-creation redirect gates on when no live
	 * `ClientRuntime` phase exists. Production never sets this; the default reads the render
	 * loop's phase exactly as before.
	 */
	@JvmStatic
	internal var testPhaseOverride: WorldPhase? = null

	/**
	 * Fallback-warning sink. Production routes to the real logger; tests that deliberately
	 * inject failures swap this out so injected-failure catches do not produce spurious
	 * warnings in the test output. [PRODUCTION_FALLBACK_LOGGER] restores the default.
	 */
	@JvmField
	internal val PRODUCTION_FALLBACK_LOGGER: (String, Throwable) -> Unit = { message, cause -> LOGGER.warn(message, cause) }

	@JvmStatic
	internal var fallbackLogger: (String, Throwable) -> Unit = PRODUCTION_FALLBACK_LOGGER

	/**
	 * The device the writer allocates the payload buffer on and precompiles the twins on.
	 * Production resolves the live Blaze3D device exactly as before; the headless evidence
	 * swaps in a recording fake device to execute the eligible production flow without one.
	 */
	@JvmStatic
	internal var deviceProvider: () -> GpuDevice = { RenderSystem.getDevice() }

	/**
	 * The cloud clock this frame's payload measures the drift from, or null when it cannot be
	 * read. Production reads the same sources `LevelRenderer.render` passes down - the level's
	 * game time and `Minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false)`; the
	 * headless evidence injects a fixed clock. A null answer (or a throwing read) is a
	 * preflight failure: the vanilla cloud pass is used and the clock state does not advance.
	 */
	@JvmStatic
	internal var currentCloudClock: () -> CloudClock? = {
		runCatching {
			val minecraft = Minecraft.getInstance()
			CloudClock(
				minecraft.level?.gameTime ?: 0L,
				minecraft.deltaTracker.getGameTimeDeltaPartialTick(false),
			)
		}.getOrNull()
	}

	/** One rendered cloud frame's game clock, the input the render call derives cloudOffset from. */
	data class CloudClock(
		val gameTime: Long,
		val partialTicks: Float,
	)

	/** One cloud frame's payload decision: the reprojection to write and whether it is invalid. */
	data class CloudPayload(
		val reprojection: Matrix4f,
		val invalid: Boolean,
	)



	/**
	 * The open scene phase the cloud pass-creation redirect gates on, or null when the pass
	 * must stay vanilla. The read is guarded so a not-yet-initialized client runtime (or the
	 * headless test JVM) degrades to the vanilla route instead of throwing.
	 */
	@JvmStatic
	fun openWorldPhase(): WorldPhase? = runCatching {
		(testPhaseOverride ?: ClientRuntime.active().activeWorldPhase())
	}.getOrNull()

	/**
	 * The cloud pattern's drift delta between two rendered frames: the game-clock advance.
	 *
	 * `cloudOffset = gameTime % (texture.width * 400) + partialTicks`, so between two frames
	 * the offset advances by `(gameTime_cur - gameTime_prev) + (partialTicks_cur -
	 * partialTicks_prev)`. The modulo wrap is exactly one pattern period (the wrap happens
	 * once per `width * 400` ticks and the pattern repeats every `width` cells), so the
	 * unwrapped clock difference is the true drift advance on every frame including the wrap.
	 */
	@JvmStatic
	fun driftDelta(previous: CloudClock, current: CloudClock): Float =
		(current.gameTime - previous.gameTime) + (current.partialTicks - previous.partialTicks)

	/**
	 * The cloud surface's world displacement for a [driftDelta] of [deltaTicks]: the constant
	 * -0.03 blocks per tick along X. A surface point at position `x` this frame sat at
	 * `x - displacement` last frame, the convention [objectReprojection] composes.
	 */
	@JvmStatic
	fun driftDisplacement(deltaTicks: Float): Vector3f =
		Vector3f(-DRIFT_BLOCKS_PER_TICK * deltaTicks, 0f, 0f)

	/**
	 * Classifies and computes the reprojection one cloud draw writes, or null when the draw
	 * must write the invalid sentinel.
	 *
	 * The displacement is the cloud pattern's per-frame drift; the reprojection is the
	 * existing object reprojection with that displacement conjugated into the camera's - the
	 * same composition the moving-block writer uses for a piston offset delta. A missing
	 * published motion, a reset frame, a missing view-projection or jitter, or a missing
	 * displacement (a rebuild frame, a clock discontinuity, or a first observation) all mean
	 * the draw must write the invalid sentinel instead of a fabricated vector. Null is that
	 * sentinel signal; the writer writes the invalid classification when this returns null.
	 */
	@JvmStatic
	fun cloudReprojection(
		motion: DlssFrameMotion?,
		currentViewProjection: Matrix4f?,
		jitter: DlssJitterOffset?,
		displacement: Vector3f?,
	): Matrix4f? {
		if (motion == null || motion.reset || currentViewProjection == null || jitter == null || displacement == null) {
			return null
		}
		return objectReprojection(motion, currentViewProjection, jitter, displacement)
	}

	/**
	 * Decides one cloud frame's payload from the open phase and the mixin's observations.
	 *
	 * The state machine: [previousClock] is advanced to this frame's clock first, so the next
	 * frame always measures from this frame regardless of this frame's own validity; the
	 * displacement is null (sentinel) when the mixin observed a mesh rebuild, when there is no
	 * previous clock (first observation), or when the clock jumped beyond
	 * [MAX_CLOCK_JUMP_TICKS] (a discontinuity); and the reprojection is null (sentinel) when
	 * the phase's motion is missing or reset or the view-projection or jitter are missing.
	 * The drift keeps composing on the frame after a rebuild or discontinuity - the rebuild
	 * invalidates only its own frame, because the mesh geometry is continuous across a cell
	 * change and the game clock keeps advancing.
	 *
	 * The clock advance happens even when the rest of the preflight later fails: the clouds
	 * still render that frame on the vanilla pass, so the next frame's drift must measure from
	 * this frame's clock.
	 */
	@JvmStatic
	internal fun buildCloudVelocityPayload(phase: WorldPhase, gameTime: Long, partialTicks: Float, meshRebuilt: Boolean): CloudPayload {
		val current = CloudClock(gameTime, partialTicks)
		val previous = previousClock
		previousClock = current
		val delta = previous?.let { driftDelta(it, current) }
		val displacement = when {
			meshRebuilt -> null
			delta == null -> null
			abs(delta) > MAX_CLOCK_JUMP_TICKS -> null
			else -> driftDisplacement(delta)
		}
		val reprojection = cloudReprojection(
			phase.activeMotion,
			phase.currentViewProjection,
			phase.activeJitter,
			displacement,
		)
		return CloudPayload(reprojection ?: IDENTITY, reprojection == null)
	}

	/**
	 * The failure-atomic preflight: every fallible writer operation the MRT pass will need,
	 * executed before the pass exists, so a failure anywhere answers false and the
	 * pass-creation redirect falls through to the exact vanilla one-attachment creation.
	 *
	 * Ordered from pure to device-touching: the cloud clock read, the payload computation
	 * (which advances the previous-clock state first - the clouds render this frame either
	 * way), the attachment-input validation (the same checks `CommandEncoder.createRenderPass`
	 * would reject, so a known-bad descriptor never reaches the encoder), the payload-buffer
	 * allocation, the twin construction plus device precompile (the exact lazy shader
	 * compilation the first bind would trigger, surfaced and validated here), and - last -
	 * the payload write on the pass's encoder. Only the write mutates encoder state, and
	 * nothing after it can fail, so a failure never leaves the encoder or the frame touched.
	 *
	 * [view] is the scene-sized velocity view the pass will write into; [colorTexture] the
	 * cloud pass's source color target, whose size the velocity attachment must match. Never
	 * throws.
	 */
	@JvmStatic
	internal fun preflightCloudPass(
		encoder: CommandEncoder,
		phase: WorldPhase,
		view: GpuTextureView,
		colorTexture: GpuTextureView,
		meshRebuilt: Boolean,
	): Boolean = try {
		val clock = currentCloudClock() ?: return false
		val payload = buildCloudVelocityPayload(phase, clock.gameTime, clock.partialTicks, meshRebuilt)
		if (!passInputsValid(view, colorTexture)) return false
		val payloadBuffer = buffer()
		preflightTwins()
		writePayload(encoder, payloadBuffer, payload, view)
		true
	} catch (failure: Throwable) {
		// Never throws: a failure at any preflight step answers false, and the pass-creation
		// redirect falls through to the exact vanilla one-attachment creation. The warning
		// keeps a repeated failure visible in the log instead of a silently degraded route.
		fallbackLogger("Cloud velocity preflight failed; the vanilla cloud pass is used", failure)
		false
	}

	/**
	 * The pass-creation interception: answers the exact vanilla one-attachment pass on every
	 * ineligible route and on every preflight or setup failure, or the fully set-up
	 * two-attachment MRT pass (latch set) when the whole eligible path succeeded.
	 *
	 * The guarded region covers the MRT pass creation and the CloudVelocityConfig uniform
	 * bind together: a failure there closes the partial pass (if one was created) so the
	 * shared encoder can still host the vanilla pass, then falls through to the exact vanilla
	 * creation. The realistic validation failures the pass constructor raises all happen
	 * before the encoder enters the pass, so the vanilla fallback always succeeds for them; a
	 * backend-level failure that leaves the shared encoder inside a pass is a device
	 * catastrophe even the vanilla pass could not recover from, and the fallback's own
	 * exception is the loud end of that frame. Never throws on any writer failure.
	 */
	@Suppress("unused")
	@JvmStatic
	fun createCloudVelocityPass(
		encoder: CommandEncoder,
		label: Supplier<String>,
		colorTexture: GpuTextureView,
		clearColor: Optional<Vector4fc>,
		depthTexture: GpuTextureView?,
		clearDepth: OptionalDouble,
		meshRebuilt: Boolean,
	): RenderPass {
		// A stale latch from a crashed frame must never misattribute this render's pass.
		CLOUD_VELOCITY_PASS.remove()
		CLOUD_SCENE_DEPTH_TEST.set(false)

		val phase = openWorldPhase() ?: return vanillaPass(encoder, label, colorTexture, clearColor, depthTexture, clearDepth)
		val velocity = phase.terrainVelocityView ?: return vanillaPass(encoder, label, colorTexture, clearColor, depthTexture, clearDepth)
		if (!preflightCloudPass(encoder, phase, velocity, colorTexture, meshRebuilt)) {
			return vanillaPass(encoder, label, colorTexture, clearColor, depthTexture, clearDepth)
		}
		val (testDepth, occludeAgainstScene) = depthForCloudVelocity(phase, colorTexture, depthTexture)

		var pass: RenderPass? = null
		try {
			val descriptor = RenderPassDescriptor.create(label)
				.withColorAttachment(colorTexture, clearColor)
				.withColorAttachment(velocity, Optional.empty())
			if (testDepth != null) {
				descriptor.withDepthAttachment(testDepth, if (occludeAgainstScene) OptionalDouble.empty() else clearDepth)
			}
			descriptor.withRenderArea(RenderPass.RenderArea(0, 0, colorTexture.getWidth(0), colorTexture.getHeight(0)))
			pass = encoder.createRenderPass(descriptor)
			// The payload binding was pre-allocated by prepare and starts at offset zero, so this
			// bind passes the alignment validation; a failure here is still inside the guarded
			// region, which recovers by closing the partial pass and falling back to vanilla.
			pass.setUniform(UNIFORM_NAME, cloudUniformSlice())
		} catch (failure: Throwable) {
			if (pass != null) {
				runCatching { pass.close() }.onFailure { closeFailure ->
					LOGGER.warn("Cloud velocity partial pass close failed while recovering a failed pass setup", closeFailure)
				}
			}
			fallbackLogger("Cloud velocity MRT pass setup failed; the vanilla cloud pass is used", failure)
			return vanillaPass(encoder, label, colorTexture, clearColor, depthTexture, clearDepth)
		}

		CLOUD_VELOCITY_PASS.set(pass)
		CLOUD_SCENE_DEPTH_TEST.set(occludeAgainstScene)
		return pass
	}

	/**
	 * The pipeline-boundary interception: swaps the source cloud pipeline for its cached
	 * two-target writer twin only when the pass is the latched MRT pass and the pipeline is
	 * one of the two preflighted cloud statics; every other pass or pipeline binds the source
	 * pipeline exactly.
	 *
	 * The twin path is no-throw by construction after a successful preflight: the twin is the
	 * precompiled cache hit from [preflightCloudPass] (the lazy shader compilation that could fail
	 * already ran there and was validated), and the pass attachment count and formats match
	 * the twin by construction, so `RenderPass.setPipeline`'s own validation passes. The
	 * preflight is the only guard - a two-target pass cannot fall back to the source
	 * one-target pipeline, so a failure at this point would have no recovery; the design
	 * makes that point unreachable instead of catching it.
	 */
	@JvmStatic
	fun bindCloudPipeline(pass: RenderPass, pipeline: RenderPipeline) {
		if (CLOUD_VELOCITY_PASS.get() === pass) {
			val twin = twinFor(pipeline)
			if (twin != null) {
				pass.setPipeline(twin)
			} else {
				// Unreachable in the mapped render call, which binds exactly the two
				// preflighted statics. A pipeline the preflight never covered cannot take the
				// twin path; binding the source pipeline into the two-target pass fails loudly
				// rather than drawing with a mismatched pipeline.
				LOGGER.error(
					"Cloud velocity pass bound unexpected pipeline {}; the source pipeline binds and the attachment mismatch fails loudly",
					pipeline.location,
				)
				pass.setPipeline(pipeline)
			}
		} else {
			pass.setPipeline(pipeline)
		}
	}

	/**
	 * The owned close seam: the source render's try-with-resources closes the writer's pass
	 * through this guard, so a device-level close failure on the latched pass is absorbed
	 * instead of throwing, and the latch is always dropped. Every other pass closes exactly as
	 * the source render closes it. Never throws for the latched pass.
	 */
	@JvmStatic
	fun closeCloudVelocityPass(pass: RenderPass) {
		if (CLOUD_VELOCITY_PASS.get() === pass) {
			try {
				pass.close()
			} catch (failure: Throwable) {
				// The pass is already marked closed and the encoder's in-pass flag is cleared
				// before the backend close runs, so the frame continues on its exact source
				// route; the warning keeps the device-level failure visible in the log.
				fallbackLogger("Cloud velocity pass close failed; absorbed", failure)
			} finally {
				CLOUD_VELOCITY_PASS.remove()
				CLOUD_SCENE_DEPTH_TEST.set(false)
			}
		} else {
			pass.close()
		}
	}

	/**
	 * The preflighted twin for [pipeline], or null when [preflightCloudPass] did not preflight it (a
	 * non-cloud pipeline, or no successful preflight this frame).
	 */
	@JvmStatic
	internal fun twinFor(pipeline: RenderPipeline): RenderPipeline? {
		if (!preflightedTwins.containsKey(pipeline)) return null
		return if (CLOUD_SCENE_DEPTH_TEST.get()) cloudSceneDepthTwin(pipeline) else preflightedTwins[pipeline]
	}

	/**
	 * Headless test seam: whether [pass] is the latched MRT pass this frame's render call is
	 * drawing into. Production never calls this; the evidence uses it to assert the latch
	 * lifecycle (set only on a fully set-up MRT pass, dropped by [closeCloudVelocityPass]).
	 */
	@JvmStatic
	internal fun isLatched(pass: RenderPass): Boolean = CLOUD_VELOCITY_PASS.get() === pass

	/**
	 * The shared payload buffer binding the cloud pass uses as `CloudVelocityConfig`.
	 *
	 * [preflightCloudPass] allocates the buffer before the MRT pass exists, so by the time [createCloudVelocityPass]
	 * binds it the allocation is a cached hit at offset zero (valid for any uniform-offset
	 * alignment). Never throws on the eligible path.
	 */
	@JvmStatic
	fun cloudUniformSlice(): GpuBufferSlice = buffer().slice()

	/**
	 * Headless test seam: drops the writer's cached payload allocation, previous clock, and
	 * preflighted-twin map so the next [preflightCloudPass] forces a fresh allocation, a first
	 * observation, and a fresh preflight. Production never calls this.
	 */
	@JvmStatic
	internal fun resetState() {
		uniformBuffer = null
		previousClock = null
		preflightedTwins.clear()
	}

	/**
	 * The attachment-input checks `CommandEncoder.createRenderPass` would raise before the
	 * encoder enters the pass: the velocity view must be alive, usable as a render attachment,
	 * and exactly the source color target's size. A rejection here is a preflight failure -
	 * the vanilla fallback runs without the encoder ever seeing the bad descriptor.
	 */
	private fun passInputsValid(view: GpuTextureView, colorTexture: GpuTextureView): Boolean {
		if (view.isClosed) return false
		if ((view.texture().usage() and GpuTexture.USAGE_RENDER_ATTACHMENT) == 0) return false
		if (view.getWidth(0) != colorTexture.getWidth(0) || view.getHeight(0) != colorTexture.getHeight(0)) return false
		return true
	}

	/**
	 * Fabulous clouds own a cleared depth buffer, so testing against it lets cloud motion write
	 * through nearer terrain. Borrow the scene depth instead when it is a different texture of
	 * the same size; keep the source depth when clouds already draw into the main target.
	 */
	private fun depthForCloudVelocity(
		phase: WorldPhase,
		colorTexture: GpuTextureView,
		sourceDepth: GpuTextureView?,
	): Pair<GpuTextureView?, Boolean> {
		val sceneDepth = phase.sceneDepthView
		if (sceneDepth != null && sceneDepthUsable(sourceDepth, sceneDepth, colorTexture)) {
			return sceneDepth to true
		}
		return sourceDepth to false
	}

	private fun sceneDepthUsable(
		sourceDepth: GpuTextureView?,
		sceneDepth: GpuTextureView,
		colorTexture: GpuTextureView,
	): Boolean = sourceDepth != null &&
		sourceDepth.texture() !== sceneDepth.texture() &&
		!sceneDepth.isClosed &&
		sceneDepth.getWidth(0) == colorTexture.getWidth(0) &&
		sceneDepth.getHeight(0) == colorTexture.getHeight(0)

	/**
	 * Constructs both cloud writer twins and forces the lazy shader compilation the first
	 * bind would trigger, on the writer's device, validating the compiled result. A failure
	 * here (a missing or broken shader, an invalid twin, a device failure) answers false from
	 * [preflightCloudPass] before any encoder or pass state exists, so the bind at [bindCloudPipeline] is a
	 * guaranteed cache hit on a valid pipeline.
	 */
	private fun preflightTwins() {
		val device = deviceProvider()
		val twins = HashMap<RenderPipeline, RenderPipeline>()
		for (source in CLOUD_PIPELINES) {
			val twin = writerTwin(source, VelocityWriter.CLOUD)
			val compiled = device.precompilePipeline(twin)
			check(compiled.isValid) { "cloud twin ${twin.location} failed to compile" }
			val occlude = cloudSceneDepthTwin(source)
			val occludeCompiled = device.precompilePipeline(occlude)
			check(occludeCompiled.isValid) { "cloud scene-depth twin ${occlude.location} failed to compile" }
			twins[source] = twin
		}
		preflightedTwins.clear()
		preflightedTwins.putAll(twins)
	}

	/**
	 * The exact vanilla cloud pass creation, reproduced verbatim: the one-attachment pass
	 * over the source color target with the source clear and depth state. This is the only
	 * fallback the interception ever returns on a writer failure.
	 */
	private fun vanillaPass(
		encoder: CommandEncoder,
		label: Supplier<String>,
		colorTexture: GpuTextureView,
		clearColor: Optional<Vector4fc>,
		depthTexture: GpuTextureView?,
		clearDepth: OptionalDouble,
	): RenderPass = encoder.createRenderPass(label, colorTexture, clearColor, depthTexture, clearDepth)

	private fun writePayload(encoder: CommandEncoder, buffer: GpuBuffer, payload: CloudPayload, view: GpuTextureView) {
		MemoryStack.stackPush().use { stack ->
			val data = Std140Builder.onStack(stack, UBO_SIZE)
				.putMat4f(payload.reprojection)
				.putVec4(
					if (payload.invalid) 1f else 0f,
					view.getWidth(0).toFloat(),
					view.getHeight(0).toFloat(),
					0f,
				)
				.get()
			encoder.writeToBuffer(buffer.slice(), data)
		}
	}

	private fun buffer(): GpuBuffer {
		return uniformBuffer ?: deviceProvider().createBuffer(
			{ "DLSS cloud velocity config" },
			GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST,
			UBO_SIZE.toLong(),
		).also { uniformBuffer = it }
	}

	/** Identity reprojection for invalid frames; never mutated, only read into the block. */
	private val IDENTITY = Matrix4f()

}
