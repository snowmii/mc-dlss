package me.snowmii.dlss

import com.mojang.blaze3d.pipeline.RenderTarget
import me.snowmii.McDlss
import org.slf4j.LoggerFactory

/**
 * Client-side holder for the stress pass, deliberately independent of the DLSS runtime.
 *
 * The pass has to run in sessions where DLSS never starts - `mc.dlss.enabled=false`, no NGX, a
 * latched native failure - because those are exactly the sessions a measurement is compared
 * against. [DlssClientRuntime] returns null in all of them, so the load cannot hang off the world
 * phase; it hangs off the same render-loop seam instead and asks the renderer which target the
 * world just went into.
 *
 * That question answers itself correctly in both routes. At the tail of `LevelRenderer.render` the
 * world-target redirect is still active, so `mainRenderTarget()` is the low-resolution scene target
 * on a DLSS frame and the real main target on a vanilla one - which is the whole comparison: the
 * same workload, paid at render resolution or at output resolution.
 */
object DlssStressRuntime {
	private val LOGGER = LoggerFactory.getLogger(McDlss.MOD_ID)

	private var pass: DlssStressPass? = null
	private var initialized = false

	/**
	 * This frame's camera, consumed by [render].
	 *
	 * Cleared on use so a frame that never reached the projection seam draws no effect rather than
	 * reconstructing its rays from the previous frame's camera.
	 */
	private var camera: DlssCameraSample? = null

	/** The pass once the render loop has built it, without ever creating it. */
	@JvmStatic
	fun activePass(): DlssStressPass? = pass

	/** Records the camera the world projection seam is about to upload. */
	@JvmStatic
	fun recordCamera(sample: DlssCameraSample) {
		camera = sample
	}

	/**
	 * Draws the stress effect over the target the world was just rendered into. Render loop only.
	 *
	 * Called on every frame including the ones that render no effect, because this is also where
	 * the pass is created: a session that starts with the load off must still be able to switch it
	 * on, and a session that starts with it on must not build GPU objects from mod init.
	 */
	@JvmStatic
	fun render(target: RenderTarget?) {
		val sample = camera
		camera = null

		val active = ensurePass() ?: return
		if (target == null || sample == null) {
			return
		}

		active.render(target, sample)
	}

	private fun ensurePass(): DlssStressPass? {
		if (initialized) {
			return pass
		}

		initialized = true
		pass = try {
			DlssStressPass.forMinecraft { message -> LOGGER.info(message) }.also {
				LOGGER.info("DLSS stress pass ready: {}", it.readout())
			}
		} catch (error: Throwable) {
			LOGGER.warn("DLSS stress pass unavailable; frames render without it", error)
			null
		}
		return pass
	}
}
