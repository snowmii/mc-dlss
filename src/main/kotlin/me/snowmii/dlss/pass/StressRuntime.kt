package me.snowmii.dlss.pass
import me.snowmii.dlss.mrt.VelocityContext
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.WorldPhase
import com.mojang.blaze3d.pipeline.RenderTarget
import me.snowmii.McDlss
import org.slf4j.LoggerFactory

/**
 * Client-side holder for the scene passes, deliberately independent of the DLSS runtime.
 *
 * A pass has to run in sessions where DLSS never starts - `mc.dlss.enabled=false`, no NGX, a
 * latched native failure - because those are exactly the sessions a measurement is compared
 * against. [ClientRuntime] returns null in all of them, so the passes cannot hang off the
 * world phase; they hang off the same render-loop seam instead and ask the renderer which
 * target the world just went into.
 *
 * That question answers itself correctly in both routes. At the tail of `LevelRenderer.render` the
 * world-target redirect is still active, so `mainRenderTarget()` is the low-resolution scene target
 * on a DLSS frame and the real main target on a vanilla one - which is the whole comparison: the
 * same workload, paid at render resolution or at output resolution.
 *
 * This holder is the prototype of a shader-loader seam: passes render in registration order, and
 * the list is where a real loader registers several. Today it owns the one stress pass.
 */
object StressRuntime {
	private val LOGGER = LoggerFactory.getLogger(McDlss.MOD_ID)

	private var passes: List<ScenePass> = emptyList()
	private var initialized = false

	/**
	 * This frame's camera, consumed by [render].
	 *
	 * Cleared on use so a frame that never reached the projection seam draws no effect rather than
	 * reconstructing its rays from the previous frame's camera.
	 */
	private var camera: DlssCameraSample? = null

	/** The passes once the render loop has built them, without ever creating them. */
	@JvmStatic
	fun activePasses(): List<ScenePass> = passes

	/** Records the camera the world projection seam is about to upload. */
	@JvmStatic
	fun recordCamera(sample: DlssCameraSample) {
		camera = sample
	}

	/**
	 * Draws every registered pass over the target the world was just rendered into, in
	 * registration order. Render loop only.
	 *
	 * Called on every frame including the ones that render no effect, because this is also where
	 * the passes are created: a session that starts with a pass off must still be able to switch
	 * it on, and a session that starts with it on must not build GPU objects from mod init.
	 *
	 * [phase] is the world phase as the tail of `LevelRenderer.render` sees it, still open on a
	 * DLSS frame and null in exactly the sessions - `mc.dlss.enabled=false`, no DLSS, a latched
	 * failure - whose frames are compared against a loaded one. The passes render either way; the
	 * phase only supplies the velocity-MRT context, so a null phase keeps every pass on its
	 * exact one-target shape.
	 */
	@JvmStatic
	fun render(target: RenderTarget?, phase: WorldPhase?) {
		val sample = camera
		camera = null

		val active = ensurePasses()
		if (target == null || sample == null || active.isEmpty()) {
			return
		}

		// The velocity write context of the open phase: the scene's RG16_FLOAT velocity view on
		// an open VELOCITY_MRT route, plus the published camera motion. A null view (vanilla,
		// camera-only, or closed phase) means no velocity write at all; a frame whose motion is
		// missing or reset still writes the view, with every pixel at the invalid sentinel.
		val velocity = phase?.terrainVelocityView?.let { view ->
			val motion = phase.activeMotion
			VelocityContext(view, motion?.reprojection, motion?.reset ?: true)
		}

		active.forEach { pass -> pass.render(target, sample, velocity) }
	}

	/**
	 * Flips every registered pass on or off and returns the joined readout, or null when no pass
	 * is registered - the key that would otherwise have nothing to say.
	 */
	@JvmStatic
	fun togglePasses(): String? {
		if (passes.isEmpty()) {
			return null
		}
		return passes.joinToString(" | ") { pass ->
			pass.toggle()
			pass.readout()
		}
	}

	private fun ensurePasses(): List<ScenePass> {
		if (initialized) {
			return passes
		}

		initialized = true
		passes = try {
			listOf(StressPass.forMinecraft { message -> LOGGER.info(message) }).also {
				LOGGER.info("DLSS scene passes ready: {}", it.joinToString(" | ") { pass -> pass.readout() })
			}
		} catch (error: Throwable) {
			LOGGER.warn("DLSS scene passes unavailable; frames render without them", error)
			emptyList()
		}
		return passes
	}
}
