package me.snowmii.dlss.session

import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.FgMultiplier
import me.snowmii.streamline.FgState

/**
 * Every native call [me.snowmii.dlss.render.RenderRuntime] makes on a running session, and
 * nothing else.
 *
 * The runtime used to take these as seven separate constructor lambdas, each defaulted to a
 * no-op and each wired in `forMinecraft` as `{ adapter.someCall() }` over one
 * [LifecycleAdapter]. That shape described the mocking strategy rather than the collaborator:
 * "which native calls does a frame make, and in what order" had to be reassembled from seven
 * scattered defaults, a test could stub one call and silently take the no-op default for the
 * rest, and adding a native call meant a parameter, a doc comment, a wiring line, and an edit
 * at every construction site.
 *
 * As one interface it is the runtime's whole native conversation in one place, and the compiler
 * can see it. [LifecycleAdapter] is the production implementation; a runtime constructed
 * without a bridge (target-only routing, tests that never reach the native side) passes null,
 * which is the same "does nothing / answers nothing" the old defaults gave.
 *
 * Deliberately *not* here: startup, the surface invalidation, the frame-support classifier, and
 * the diagnostics sink. Those are adapters over Minecraft and its configuration rather than
 * calls on the session, and they stay injected functions so this module keeps no engine import.
 */
interface SessionBridge {
	/**
	 * Re-queries the native render dimensions for a mode and preset chosen while the session
	 * runs, or null when the reconfigure was refused. Never re-initializes the session.
	 */
	fun reconfigure(qualityMode: SRMode, renderPreset: SRModelPreset): Dimensions?

	/**
	 * Blocks until the device has finished every frame already submitted.
	 *
	 * Releasing the scene target and the native images frees GPU objects that Minecraft's still
	 * in-flight frames read from. Nothing on the CPU side observes that: the key that triggered
	 * the release is polled between frames, the release itself succeeds, and the device is lost
	 * several frames later inside an unrelated semaphore wait.
	 */
	fun waitDeviceIdle(): Boolean

	/**
	 * Blocks until Streamline's DLSS-G input processing for the previously presented frame has
	 * completed.
	 *
	 * Runs on the render thread at the start of every FG-active frame, before the world phase
	 * rewrites the DLSS-G-tagged inputs (the scene depth, the native motion image, and the
	 * HUD-less and UI targets). Under the recorded eBlockNoClientQueues mode the plugin reads
	 * those inputs asynchronously after Present, so the wait is what retires the resource-reuse
	 * race between the previous frame's DLSS-G processing and this frame's rewrites. A wait
	 * failure latches the session through the implementation; the routing decision then reads
	 * the latched state and degrades the frame to vanilla.
	 */
	fun waitFgInputsIdle(): Boolean

	/**
	 * Reads the live DLSS-G state for the per-frame status poll, or null when the session
	 * cannot answer.
	 *
	 * Polled on the render thread at the start of every FG-active frame, after the input wait:
	 * a reported status other than eDLSSGStatusOk (word zero) suspends composition. A null read
	 * is no information, not a verdict - a refused query must not suspend on the plugin's
	 * behalf.
	 */
	fun queryFgState(): FgState?

	/**
	 * Re-records the DLSS-G options in the eOff mode (retained resources) when FG switches off:
	 * the status suspension, the user toggle, and the frame-support suspension all go through
	 * it, each recording exactly once on its own transition. The SR session stays READY, so
	 * this is deliberately not a session-latching call.
	 */
	fun recordFrameGenerationOff(): Boolean

	/**
	 * Reads the stored FG multiplier and the device's numFramesToGenerateMax, or null when the
	 * session cannot answer: the F12 cycle computes its next value from this, so a null answer
	 * means no cycle.
	 */
	fun queryFgMultiplier(): FgMultiplier?

	/**
	 * Records a new FG multiplier. The native side validates the value against the device
	 * ceiling, and a refusal leaves the multiplier in effect unchanged.
	 */
	fun setFgMultiplier(numFramesToGenerate: Int): Boolean

	/**
	 * Records the Reflex frame-rate cap, in microseconds per frame; zero is no Reflex-side cap.
	 *
	 * Called from the frame seam that reads Minecraft's own framerate limit, so it answers on
	 * every frame; the native side records only when the cap actually changes. The return is
	 * whether the cap the caller asked for is the one now in effect, and it decides whether
	 * Minecraft's own limiter may be suppressed: a session with no Reflex limiter behind it must
	 * keep the engine's, or the frames run uncapped.
	 *
	 * Defaulted to "no Reflex limiter" so a bridge that predates the cap - the test doubles, and
	 * any native build without the export - keeps Minecraft's limiter rather than losing both.
	 */
	fun recordReflexFrameLimit(microsecondsPerFrame: Int): Boolean = false
}
