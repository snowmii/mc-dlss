package me.snowmii.dlss.bridge;

import me.snowmii.streamline.CameraConstants;
import me.snowmii.streamline.Dimensions;
import me.snowmii.streamline.EvaluationImages;
import me.snowmii.streamline.EvaluationRequest;
import me.snowmii.streamline.FgMultiplier;
import me.snowmii.streamline.FgState;
import me.snowmii.streamline.FgTagRequest;
import me.snowmii.streamline.FillVelocityRequest;
import me.snowmii.streamline.FrameTimings;
import me.snowmii.streamline.ImageBinding;
import me.snowmii.streamline.MotionRequest;
import me.snowmii.streamline.PresentMarkerEvents;
import me.snowmii.streamline.PresentTarget;
import me.snowmii.streamline.SlQueueRequirements;
import me.snowmii.streamline.SrTagRequest;
import me.snowmii.streamline.TaggedFrameIndexes;

import java.nio.file.Path;
import java.util.List;

/**
 * Native lifecycle calls exposed to the session adapter and test doubles.
 *
 * <p>The per-frame recording calls take a request object rather than a long argument list,
 * because that is the shape the flat ABI itself now takes and because the alternative was a
 * signature in which two adjacent {@code long} handles could be transposed by either side
 * without any diagnostic. The request types are defined in this package for the same reason
 * this interface is: they are the vocabulary of the ABI, not of the renderer that fills them.
 */
public interface NativeApi {
	/** Flat ABI success result defined by native/mc_dlss.h. */
	int SUCCESS_RESULT = 1;

	/**
	 * Validates and records the live Vulkan tuple the bridge's own images and motion pass are
	 * allocated against, and nothing else: the retired direct-NGX initialization no longer runs
	 * behind it.
	 *
	 * <p>Must be called after {@link Native#bootstrapStreamline(Path)} and
	 * {@link Native#activateVulkanProxies(long, long, long, int, int, int, int)} with the same
	 * handles that were handed to {@code slSetVulkanInfo}; an initialize that ran before proxy
	 * activation - or with a tuple that disagrees with the recorded proxy tuple - is refused and
	 * records nothing.
	 *
	 * <p>{@code sdkPath} and {@code dataPath} are compatibility inputs. The retired direct-NGX
	 * implementation used them to locate its feature DLL and data; nothing in the Streamline
	 * stack consumes them, so they are validated as well-formed paths and otherwise ignored.
	 *
	 * <p>The first successful transition also records the Reflex options registration the
	 * pinned Reflex guide requires: one {@code slReflexSetOptions} call with
	 * {@code sl::ReflexMode::eLowLatency}, retained in native state and reported by
	 * {@link #queryReflexOptions()}. A failed registration does not fail the transition -
	 * Reflex registration must not gate the SR/FG session - the oracle reports whether it
	 * succeeded.
	 */
	int initialize(
		long vkInstance,
		long vkPhysicalDevice,
		long vkDevice,
		Path sdkPath,
		Path dataPath
	);

	Dimensions queryOptimalDimensions(
		int outputWidth,
		int outputHeight,
		int qualityMode
	);

	/**
	 * Stores the dimensions, the NGX-valued performance/quality mode, and the render preset
	 * the SR configuration uses, and records them with Streamline's {@code slDLSSSetOptions}.
	 *
	 * <p>{@code renderPreset} is an {@code NVSDK_NGX_DLSS_Hint_Render_Preset} value (J, K, L, or
	 * M). The bridge writes it onto the preset field {@code sl::DLSSOptions} carries for the
	 * mode, which is how a preset change reaches the running model.
	 *
	 * <p>These dimensions are what everything else is sized from: the images {@link
	 * #acquireImages()} allocates and the sizes the recording calls check their callers
	 * against.
	 */
	int configure(
		int outputWidth,
		int outputHeight,
		int renderWidth,
		int renderHeight,
		int qualityMode,
		int renderPreset
	);

	/**
	 * Records the DLSS-G per-frame options with Streamline's {@code slDLSSGSetOptions}:
	 * mode on, the stored multiplier (one generated frame per real one, 2x, by default),
	 * retained resources while off, UI
	 * recomposition, the queue mode, the declared back-buffer count, the render/output
	 * extents from the stored configuration, and the five required formats.
	 *
	 * <p>Must be called after bootstrap and proxy activation and after a successful {@link
	 * #configure(int, int, int, int, int, int)}: the record answers {@code FAIL_NotInitialized}
	 * without a ready Streamline session and {@code FAIL_InvalidParameter} while the stored
	 * dimensions are still zero.
	 *
	 * <p>{@code numBackBuffers} is the swapchain's expected image count, declared as the
	 * caller knows it; adequacy against Streamline's requirement is verified live later in the
	 * milestone.
	 *
	 * <p>Default-implemented so the pre-SL test doubles that stand in for the bridge do not
	 * have to declare a call they never reach; {@link Native} overrides it.
	 */
	default int configureFg(int numBackBuffers) {
		throw new UnsupportedOperationException("configureFg");
	}

	/**
	 * Switches the recorded DLSS-G options' mode through {@code slDLSSGSetOptions}: {@code eOn}
	 * when {@code fgEnabled} is non-zero, {@code eOff} when it is zero.
	 *
	 * <p>The mode record is the status-latch fallback's native half: after the per-frame
	 * {@link #queryFgState()} poll reports a status other than {@code eDLSSGStatusOk} while FG
	 * is active, the session re-records the options in the {@code eOff} mode so the plugin
	 * stops interpolating, with the retained-resources flag keeping its allocations alive and
	 * the same back-buffer count and extents the validated {@code eOn} record stored. Answers
	 * {@code FAIL_NotInitialized} without a ready Streamline session and
	 * {@code FAIL_InvalidParameter} while no DLSS-G options record is stored - the mode
	 * record switches an existing record, it never creates one. The re-arm refusal that keeps
	 * {@code eOn} from coming back for the session is the Kotlin policy's, not this record's.
	 *
	 * <p>Default-implemented so the pre-SL test doubles that stand in for the bridge do not
	 * have to declare a call they never reach; {@link Native} overrides it.
	 */
	default int setFgMode(int fgEnabled) {
		throw new UnsupportedOperationException("setFgMode");
	}

	/**
	 * Records the DLSS-G frame multiplier through {@code slDLSSGSetOptions}:
	 * {@code numFramesToGenerate} generated frames per rendered one (1 = 2x, 2 = 3x, and so
	 * on). The native side validates the value against the device's
	 * {@code DLSSGState::numFramesToGenerateMax} read fresh from {@code slDLSSGGetState}, so
	 * an unsupported multiplier is refused rather than recorded; a refusal changes nothing.
	 *
	 * <p>The record keeps the validated eOn record's shape - mode, retained resources,
	 * back-buffer count, extents, formats - and changes only {@code numFramesToGenerate}.
	 * Answers {@code FAIL_NotInitialized} without a ready Streamline session and
	 * {@code FAIL_InvalidParameter} while no DLSS-G options record is stored or the value is
	 * outside 1..max.
	 *
	 * <p>Default-implemented so the pre-SL test doubles that stand in for the bridge do not
	 * have to declare a call they never reach; {@link Native} overrides it.
	 */
	default int setFgMultiplier(int numFramesToGenerate) {
		throw new UnsupportedOperationException("setFgMultiplier");
	}

	/**
	 * The multiplier oracle: the {@code numFramesToGenerate} the recorded DLSS-G options
	 * carry and the device's {@code numFramesToGenerateMax}, read fresh from
	 * {@code slDLSSGGetState} by {@code mc_dlss_query_fg_multiplier}. The cycle the F12 key
	 * drives wraps against the max so an unsupported multiplier is never offered.
	 *
	 * <p>Answers {@code FAIL_NotInitialized} while the Streamline session is not ready and
	 * {@code FAIL_InvalidParameter} while the DLSS-G options have not recorded, the same
	 * gates as {@link #queryFgState()}. The read performs no GPU work and never blocks.
	 *
	 * <p>Default-implemented for the same reason as {@link #queryFgState()}.
	 */
	default FgMultiplier queryFgMultiplier() {
		throw new UnsupportedOperationException("queryFgMultiplier");
	}

	/**
	 * Returns the native-owned motion and output images for the configured dimensions,
	 * creating them on first use and reusing them while that configuration holds.
	 *
	 * <p>Reported so the caller can see what it is rendering through and drive their release.
	 * They are never passed back in for an evaluation - the bridge reaches its own images
	 * directly.
	 */
	EvaluationImages acquireImages();

	int releaseImages();

	/**
	 * Blocks until the Vulkan device has finished everything submitted to it.
	 *
	 * <p>Called before releasing anything the recorded frames referenced - the engine's
	 * low-resolution render target as much as the native images - because Minecraft's Vulkan
	 * backend keeps frames in flight and freeing a resource one of them still reads loses the
	 * device.
	 */
	int waitDeviceIdle();

	/**
	 * GPU timings of the last frame that completed all three recorded stages, or null when none
	 * has yet or the device cannot timestamp graphics work.
	 *
	 * <p>Never waits on the GPU: the result describes a frame several frames old.
	 */
	FrameTimings frameTimings();

	/**
	 * Records the camera-only motion pass that fills the native motion image from the engine's
	 * depth image, on the caller's command buffer.
	 *
	 * <p>This has to precede {@link #evaluate} on the same buffer: the evaluation reads the image
	 * this pass writes, and the pass ends with the barrier that makes those writes visible.
	 */
	int writeMotion(MotionRequest request);

	/**
	 * Records the post-scene velocity merge on the caller's command buffer: one dispatch samples
	 * the engine's depth image and its sparse RG16_FLOAT velocity companion, copies every
	 * non-sentinel object vector unchanged and reconstructs jitter-stripped camera motion for
	 * every sentinel pixel, and writes the complete merged field into the native motion image.
	 * On a reset frame the dispatch writes the invalid sentinel everywhere instead. The
	 * companion is a sampled input only and is never bound as storage.
	 *
	 * <p>This has to precede {@link #tagSrResources} on the same buffer on the velocity-MRT
	 * route: the native motion image is the sole Streamline motion source, and the evaluation
	 * reads it.
	 *
	 * <p>Default-implemented so the pre-SL test doubles that stand in for the bridge do not
	 * have to declare a call they never reach; {@link Native} overrides it.
	 */
	default int fillVelocity(FillVelocityRequest request) {
		throw new UnsupportedOperationException("fillVelocity");
	}

	/**
	 * Records the copy of the upscaled output image into an engine target, on the caller's
	 * command buffer.
	 *
	 * <p>This has to follow {@link #evaluate} on the same buffer: it copies the image the
	 * evaluation writes, and the destination is what the rest of the frame composes over.
	 */
	int presentOutput(PresentTarget target);

	/**
	 * Records the DLSS evaluation on the caller's command buffer.
	 *
	 * <p>The request carries only the engine's colour and depth. The motion and output images
	 * are the bridge's own, allocated by {@link #acquireImages()} from the configured
	 * dimensions, so handing them back would be the caller returning handles the bridge already
	 * holds.
	 */
	int evaluate(EvaluationRequest request);

	/**
	 * Tags one frame's DLSS SR resources on the caller's command buffer, through Streamline's
	 * frame-based resource tagging ({@code slGetNewFrameToken} + {@code slSetTagForFrame}).
	 *
	 * <p>The request carries the engine's colour and depth. The motion source is always the
	 * bridge's own motion image - filled by {@link #writeMotion} on the camera-only route and
	 * by {@link #fillVelocity} on the velocity-MRT route - so no engine velocity companion
	 * crosses on the tag. The bridge's own motion and output images are tagged from native
	 * state when they have been acquired for the configured dimensions; until then the call
	 * still succeeds with just the engine's inputs.
	 *
	 * <p>Default-implemented so the pre-SL test doubles that stand in for the bridge do not
	 * have to declare a call they never reach; {@link Native} overrides it.
	 */
	default int tagSrResources(SrTagRequest request) {
		throw new UnsupportedOperationException("tagSrResources");
	}

	/**
	 * Tags one frame's DLSS-G resources on the caller's command buffer, through Streamline's
	 * frame-based resource tagging ({@code slGetNewFrameToken} + {@code slSetTagForFrame}).
	 *
	 * <p>The request carries the engine's render-sized depth (D32_SFLOAT) and its output-sized
	 * HUD-less colour and UI colour+alpha targets (both R8G8B8A8_UNORM); the formats must match
	 * the ones {@link #configureFg} recorded, and anything else is refused. The motion source
	 * is always the bridge's own motion image - filled by {@link #writeMotion} on the
	 * camera-only route and by {@link #fillVelocity} on the velocity-MRT route - so no engine
	 * velocity companion crosses on the tag. The call is refused until {@link #configureFg}
	 * recorded the DLSS-G options and the bridge's own motion image was acquired for the
	 * configured dimensions: the frame's four tags always record together, never as a partial
	 * set. The backbuffer/output chain is present interception rather than a tag, so no output
	 * image is carried.
	 *
	 * <p>The frame token this call obtains and retains is shared with {@link #tagSrResources}
	 * for the same frame: a repeated tag reuses the token rather than advancing the frame, and
	 * the frame's evaluation consumes it.
	 *
	 * <p>Default-implemented so the pre-SL test doubles that stand in for the bridge do not
	 * have to declare a call they never reach; {@link Native} overrides it.
	 */
	default int tagFgResources(FgTagRequest request) {
		throw new UnsupportedOperationException("tagFgResources");
	}

	/**
	 * Records the frame's present-handoff eligibility: re-records the stored DLSS-G 2x
	 * options through {@code slDLSSGSetOptions} with the back-buffer count the last
	 * successful {@link #configureFg(int)} declared, accepting exactly one complete
	 * current-frame SR+FG tag set under equal frame indexes.
	 *
	 * <p>Missing options, partial tags (one of the two tag sets never recorded), and
	 * consumed eligibility (the frame's tag set already handed off) are refused without
	 * side effects: a refused handoff clears no tag state and re-records no options. The
	 * call owns no command buffer and records no GPU work - the frame's tagged resources
	 * stay in the layouts the tags declared until Streamline's present path consumes them.
	 *
	 * <p>Default-implemented so the pre-SL test doubles that stand in for the bridge do not
	 * have to declare a call they never reach; {@link Native} overrides it.
	 */
	default int presentHandoff() {
		throw new UnsupportedOperationException("presentHandoff");
	}

	/**
	 * Emits the PRESENT_START Reflex marker of the armed present bracket under the frame's
	 * retained token, immediately before the queue present. The DLSS-G plugin correlates the
	 * presented frame with its common constants through this marker, so a frame presented
	 * without it never generates.
	 *
	 * <p>An unarmed present is a no-op success: an SR-only or skipped frame, or any present
	 * without a successful {@link #presentHandoff()} arming the bracket, has no bracket to
	 * open - and the present seam fires on every present, so a refusal would latch the
	 * session on a frame that simply did not compose. Only a bracket a successful handoff
	 * armed emits the START, and exactly once: a present that threw between START and END
	 * leaves the bracket open, and a second START for the same frame is the same no-op.
	 * Answers {@code FAIL_NotInitialized} while the session is not ready.
	 *
	 * <p>Default-implemented for the same reason as {@link #presentHandoff()}.
	 */
	default int presentStart() {
		throw new UnsupportedOperationException("presentStart");
	}

	/**
	 * Emits the PRESENT_END Reflex marker of the armed present bracket under the frame's
	 * retained token, immediately after the queue present returned, and consumes the
	 * bracket: the retained token and the tag set's handoff eligibility clear whether the
	 * marker succeeded or failed, so the next frame's tags obtain a fresh token under a
	 * fresh index.
	 *
	 * <p>An unarmed present has no bracket to close and is the same no-op success as the
	 * START. The END marker closes only a bracket a START actually opened - a START that
	 * failed never leaves an open bracket, and the log must never read an END without its
	 * START - but an armed bracket whose START never emitted is consumed here exactly like
	 * a successful one, so a failed START cannot leave a stale bracket for a later present
	 * to open. Answers {@code FAIL_NotInitialized} while the session is not ready.
	 *
	 * <p>Default-implemented for the same reason as {@link #presentHandoff()}.
	 */
	default int presentEnd() {
		throw new UnsupportedOperationException("presentEnd");
	}

	/**
	 * The present-marker oracle: how many PRESENT_START and PRESENT_END markers this module
	 * has actually emitted (per-type cumulative counts), the total event count, and the
	 * recent event log in emission order, as reported by {@code mc_dlss_query_present_markers}.
	 *
	 * <p>Each event names the marker type and the Streamline frame index (the retained frame
	 * token) it was emitted under. The index must equal the frame indexes the frame's SR/FG
	 * tags and its common constants recorded under: the handoff emits both markers against
	 * the same retained frame token the tags and the constants used, so the events' index
	 * equality with {@link #taggedFrameIndexes()} is what proves the present bracket
	 * correlates with the frame DLSS-G generates. The per-type counts must each advance by
	 * exactly one per successful handoff and stay unchanged across refused or pre-ready
	 * handoffs, which is what proves the "exactly one PRESENT_START then PRESENT_END" half
	 * of the present-marker contract: the START and END events are recorded separately and
	 * in emission order, so a handoff whose END marker failed reads as one START event and
	 * no END rather than as a pair that never happened.
	 *
	 * <p>Answers {@code FAIL_NotInitialized} until at least one marker was actually emitted.
	 * Default-implemented for the same reason as {@link #queryDeviceFeatures12()}.
	 */
	default PresentMarkerEvents presentMarkers() {
		throw new UnsupportedOperationException("presentMarkers");
	}

	/** Installs the Win32 message hook that receives PCL's periodic latency-stat ping. */
	default int installPclWindow(long hwnd) {
		throw new UnsupportedOperationException("installPclWindow");
	}

	/**
	 * Starts the frame's Reflex work at Minecraft's GLFW input poll seam.
	 * The native side obtains the frame token, runs {@code slReflexSleep}, and emits
	 * {@code ePCLatencyPing} only when the installed window hook received
	 * {@code PclState::statsWindowMessage}.
	 */
	default int reflexInputSample() {
		throw new UnsupportedOperationException("reflexInputSample");
	}

	/**
	 * Emits one Reflex/PCL frame marker under the frame's retained token, as reported by
	 * {@link #reflexMarkers()} and named by the same {@link ReflexMarkerType} vocabulary.
	 *
	 * <p>The marker travels as a value rather than as a method each, because that is the shape
	 * the flat ABI takes ({@code mc_dlss_reflex_marker}) and because a marker added later is
	 * then an enum constant rather than one new method on every layer between the mixin and
	 * Streamline. The four this call emits are the simulation pair around Minecraft's runTick
	 * simulation and the render-submit pair around renderFrame's
	 * {@code CommandEncoder.submit()}.
	 *
	 * <p>{@link ReflexMarkerType#INPUT_SAMPLE} is refused with
	 * {@code FAIL_InvalidParameter}: that seam obtains the frame's token and runs
	 * {@code slReflexSleep} before it emits anything, so it stays {@link #reflexInputSample()}
	 * rather than becoming a marker value this call could stand in for. Answers
	 * {@code FAIL_NotInitialized} while the session is not ready or no frame token is retained
	 * (a frame that never ran its input sample emits no markers). Default-implemented for the
	 * same reason as {@link #reflexInputSample()}.
	 */
	default int reflexMarker(ReflexMarkerType type) {
		throw new UnsupportedOperationException("reflexMarker");
	}

	/**
	 * The reflex-marker oracle: how many of each of the five Reflex/PCL markers this module
	 * has actually emitted (per-type cumulative counts), the total event count, and the
	 * recent event log in emission order, as reported by
	 * {@code mc_dlss_query_reflex_markers}.
	 *
	 * <p>Each event names the marker type and the Streamline frame index (the retained
	 * frame token) it was emitted under. The index must equal the frame indexes the frame's
	 * SR/FG tags, its common constants, and its present markers recorded under: the input
	 * sample obtains the retained token the rest of the frame reuses, so the events' index
	 * equality with {@link #taggedFrameIndexes()} is what proves the marker surface shares
	 * the retained token identity. The per-type counts advance by exactly one per emitted
	 * marker and stay unchanged across refused or pre-ready calls, which is what proves the
	 * "refused sessions emit none" half of the M-12 contract.
	 *
	 * <p>Answers {@code FAIL_NotInitialized} until at least one marker was actually emitted.
	 * Default-implemented for the same reason as {@link #presentMarkers()}.
	 */
	default ReflexMarkerEvents reflexMarkers() {
		throw new UnsupportedOperationException("reflexMarkers");
	}

	/**
	 * The Reflex registration the READY transition recorded, as reported by
	 * {@code mc_dlss_query_reflex_options}: the {@code sl::ReflexMode} value the
	 * {@code slReflexSetOptions} call carried and how many such calls this session made.
	 *
	 * <p>{@link #initialize(long, long, long, Path, Path)} makes the one call the pinned
	 * Reflex guide requires - even with Reflex Low Latency off and no Reflex UI - and the
	 * guide says not to repeat it per frame while the options do not change, so the oracle
	 * answers {@code eLowLatency} and a call count of one after the READY transition, and
	 * the count stays one across idempotent re-initializes, composed frames, and resets:
	 * the exactly-once-at-READY proof.
	 *
	 * <p>Answers {@code FAIL_NotInitialized} while the Streamline session is not ready and
	 * {@code FAIL_InvalidParameter} while no record exists. Default-implemented for the
	 * same reason as {@link #reflexMarkers()}.
	 */
	default ReflexRegistration queryReflexOptions() {
		throw new UnsupportedOperationException("queryReflexOptions");
	}

	/**
	 * Re-records the Reflex options with a frame-rate cap: {@code frameLimitUs} microseconds per
	 * frame, {@code 0} for no Reflex-side cap.
	 *
	 * <p>Reflex's limiter is the frame-rate cap DLSS-G tolerates: it sleeps at the start of the
	 * frame, before simulation, where the driver is aware of the pacer's schedule. Minecraft's
	 * own {@code FramerateLimiter} parks and spin-waits after Present instead, and the jitter it
	 * leaves in the app frame interval is what multi-frame generation divides N ways - every
	 * sub-interval carries the whole error, so the wobble grows with the multiplier.
	 *
	 * <p>Answers {@code FAIL_NotInitialized} while the Streamline session is not ready and
	 * {@code FAIL_InvalidParameter} while the READY transition's Reflex registration never
	 * recorded. A cap already in effect records nothing and answers success, so the frame seam
	 * that reads Minecraft's own limit may call this every frame.
	 *
	 * <p>Default-implemented so the pre-SL test doubles that stand in for the bridge do not have
	 * to declare a call they never reach; {@link Native} overrides it.
	 */
	default int recordReflexFrameLimit(int frameLimitUs) {
		throw new UnsupportedOperationException("recordReflexFrameLimit");
	}

	/**
	 * Blocks until Streamline's DLSS-G input processing for the previously presented frame has
	 * completed, on the caller's (present/render) thread and through the Vulkan device.
	 *
	 * <p>The DLSS-G options record the {@code eBlockNoClientQueues} queue-parallelism mode,
	 * under which the DLSS-G plugin reads the tagged inputs of a presented frame on its own
	 * queues after Present; the programming guide requires the host to wait on
	 * {@code DLSSGState::inputsProcessingCompletionFence} - a Vulkan timeline semaphore on this
	 * API, read together with its value {@code DLSSGState::lastPresentInputsProcessingCompletionFenceValue}
	 * via {@code slDLSSGGetState} - before it modifies or destroys those inputs in a later frame.
	 * This is the call the mod makes at the start of an FG-active frame, before the world phase
	 * rewrites the tagged depth, motion, HUD-less, and UI inputs.
	 *
	 * <p>Answers {@code FAIL_NotInitialized} while the Streamline session is not ready and
	 * {@code FAIL_InvalidParameter} while the DLSS-G options have not recorded for the stored
	 * configuration; a null semaphore (no input processing in flight, as before the first
	 * present) is a no-op success. The bridge does not look at the reported DLSS-G status: the
	 * status-to-off fallback is a later slice's own.
	 *
	 * <p>Default-implemented so the pre-SL test doubles that stand in for the bridge do not
	 * have to declare a call they never reach; {@link Native} overrides it.
	 */
	default int waitFgInputsIdle() {
		throw new UnsupportedOperationException("waitFgInputsIdle");
	}

	/**
	 * The wait oracle: performs the same value-aware Vulkan timeline-semaphore wait
	 * {@link #waitFgInputsIdle()} performs, on explicit device and semaphore handles and an
	 * explicit value, so the wait's value semantics are provable without a live Streamline
	 * session. The headless proof creates its own timeline semaphore, waits for a value the
	 * semaphore has not reached, and observes the call block until that value is signaled;
	 * waiting for any lower value (or treating the semaphore as a VkFence) would answer
	 * immediately and fail the proof.
	 *
	 * <p>Answers {@code FAIL_InvalidParameter} when either handle is null. Touches no module
	 * or Streamline state and blocks on the device like the session-driven entry.
	 *
	 * <p>Default-implemented so the pre-SL test doubles that stand in for the bridge do not
	 * have to declare a call they never reach; {@link Native} overrides it.
	 */
	default int waitFgInputsValue(long vkDevice, long semaphore, long value) {
		throw new UnsupportedOperationException("waitFgInputsValue");
	}

	/**
	 * One snapshot of Streamline's live DLSS-G state, read through {@code slDLSSGGetState}
	 * by {@code mc_dlss_query_fg_state}: the raw {@code DLSSGStatus} word (zero is
	 * {@code eDLSSGStatusOk}, every failure is its own bit), actual presentations per app frame
	 * (two means one real plus one generated), the value the input-processing completion
	 * timeline semaphore last reached for the presented frames'
	 * inputs, and the semaphore handle itself.
	 *
	 * <p>The present-generation proof reads this to observe the interposed {@code vkQueuePresentKHR}
	 * path working: the status word after presents, a presentation factor above one, and a
	 * completion-fence value that advances with every presented
	 * frame the plugin processed - the same value {@link #waitFgInputsIdle()} waits on, read
	 * from the same query, so the two always travel together. Answers {@code FAIL_NotInitialized}
	 * while the Streamline session is not ready and {@code FAIL_InvalidParameter} while the
	 * DLSS-G options have not recorded, the same gates as {@link #tagFgResources(FgTagRequest)}.
	 * The read performs no GPU work and never blocks.
	 *
	 * <p>Default-implemented so the pre-SL test doubles that stand in for the bridge do not
	 * have to declare a query they never reach; {@link Native} overrides it.
	 */
	default FgState queryFgState() {
		throw new UnsupportedOperationException("queryFgState");
	}

	/**
	 * The camera constants the last successful evaluation recorded into Streamline's common
	 * constants, as reported by {@code mc_dlss_query_camera_constants}: the jitter-free
	 * row-major view-to-clip, clip-to-view, and clip-to-prev-clip matrices, the frustum
	 * scalars, and the camera's world-space position and orthonormal right/up/forward basis,
	 * exactly as {@link #evaluate(EvaluationRequest)} carried them.
	 *
	 * <p>The constants oracle proves the caller's camera reached {@code slSetConstants}
	 * unchanged - the constants the DLSS-G plugin interpolates the generated frame's camera
	 * from. Answers {@code FAIL_NotInitialized} until an evaluation recorded constants at
	 * least once.
	 *
	 * <p>Default-implemented for the same reason as {@link #queryFgState()}.
	 */
	default CameraConstants queryCameraConstants() {
		throw new UnsupportedOperationException("queryCameraConstants");
	}

	/**
	 * The camera constants the last composed frame's FG-side record carried into
	 * Streamline's per-frame constants, as reported by {@code mc_dlss_query_fg_camera_constants}.
	 *
	 * <p>The composed frame's evaluation records the same camera twice, once on the SR
	 * viewport and once on the FG viewport, both under the same retained frame token: the
	 * DLSS-G plugin reads per-frame constants from the viewport its options, state, and tags
	 * were recorded on, and after the viewport split the SR viewport's record no longer
	 * reaches it. This oracle reports exactly the FG-viewport record, independently of
	 * {@link #queryCameraConstants()}: an SR-only evaluation establishes the SR record and
	 * never this one, and only a frame whose FG tag recorded before the evaluation
	 * establishes it. The FG record carries the FG viewport's orientation - the four
	 * clip-space matrices conjugate with F = diag(1,-1,1,1) and the jitter's y negates,
	 * matching the y-flipped copies the FG tag names - while the SR oracle reports the raw
	 * record. Answers {@code FAIL_NotInitialized} until a composed frame's FG-side
	 * record succeeded at least once.
	 *
	 * <p>Default-implemented for the same reason as {@link #queryCameraConstants()}.
	 */
	default CameraConstants queryFgCameraConstants() {
		throw new UnsupportedOperationException("queryFgCameraConstants");
	}

	/**
	 * The module-owned FG orientation copies the {@link #tagFgResources(FgTagRequest)} tag
	 * names: the backbuffer-oriented flipped depth (render-sized D32_SFLOAT), HUD-less and UI
	 * colours (output-sized RGBA8_UNORM), and the flipped motion image (render-sized
	 * R16G16_SFLOAT) whose y component the motion dispatches negate.
	 *
	 * <p>Created and released with the SR motion and output images, from the same configured
	 * dimensions; reported by {@code mc_dlss_query_fg_images} so the orientation rung can read
	 * their content back. Answers {@code FAIL_NotInitialized} before the images were acquired
	 * for the stored configuration.
	 *
	 * <p>Default-implemented for the same reason as {@link #queryCameraConstants()}.
	 */
	default FgOrientationImages queryFgImages() {
		throw new UnsupportedOperationException("queryFgImages");
	}

	/**
	 * The four FG orientation copies {@link #queryFgImages()} reports: the flipped depth,
	 * HUD-less, and UI copies and the flipped motion image, in tag order.
	 */
	record FgOrientationImages(
		ImageBinding depth,
		ImageBinding hudless,
		ImageBinding ui,
		ImageBinding motion
	) {}

	/**
	 * The deduplicated Vulkan 1.2 feature names Streamline's loaded features (DLSS, DLSS-G,
	 * Reflex) require the device to enable, as {@code slGetFeatureRequirements} reports them
	 * through {@code mc_dlss_query_device_feature_12}.
	 *
	 * <p>Default-implemented so the pre-SL test doubles that stand in for the bridge do not
	 * have to declare a query they never reach; {@link Native} overrides it.
	 */
	default List<String> queryDeviceFeatures12() {
		throw new UnsupportedOperationException("queryDeviceFeatures12");
	}

	/**
	 * The deduplicated Vulkan 1.3 feature names Streamline's loaded features require, as
	 * {@code slGetFeatureRequirements} reports them through {@code mc_dlss_query_device_feature_13}.
	 *
	 * <p>Default-implemented for the same reason as {@link #queryDeviceFeatures12()}.
	 */
	default List<String> queryDeviceFeatures13() {
		throw new UnsupportedOperationException("queryDeviceFeatures13");
	}

	/**
	 * The Streamline frame indices the last {@link #tagSrResources} and {@link #tagFgResources}
	 * calls tagged under, as the runtime numbered them through {@code slGetNewFrameToken} and
	 * reported by {@code mc_dlss_query_tagged_frame_indexes}.
	 *
	 * <p>One frame's SR and FG tags must land under the same index: the FG tag reuses the frame
	 * token the SR tag obtained and retained rather than calling {@code slGetNewFrameToken}
	 * again, and equality of the pair is the behavior-level oracle the composed rung asserts. A
	 * tag that advanced the frame instead would record a strictly later index under its slot.
	 *
	 * <p>Default-implemented for the same reason as {@link #queryDeviceFeatures12()}.
	 */
	default TaggedFrameIndexes taggedFrameIndexes() {
		throw new UnsupportedOperationException("taggedFrameIndexes");
	}

	/**
	 * The extra Vulkan queues Streamline's loaded features require the host to create, summed
	 * across features as {@code slGetFeatureRequirements} reports them through
	 * {@code mc_dlss_query_queue_requirements}.
	 *
	 * <p>Default-implemented for the same reason as {@link #queryDeviceFeatures12()}.
	 */
	default SlQueueRequirements queryQueueRequirements() {
		throw new UnsupportedOperationException("queryQueueRequirements");
	}

	/**
	 * The five Reflex/PCL frame markers this module emits at the M-12 input, simulation, and
	 * render-submit seams, as the native event log tags each actually-emitted marker with.
	 */
	enum ReflexMarkerType {
		INPUT_SAMPLE(0),
		SIMULATION_START(1),
		SIMULATION_END(2),
		RENDER_SUBMIT_START(3),
		RENDER_SUBMIT_END(4);

		private final int nativeValue;

		ReflexMarkerType(final int nativeValue) {
			this.nativeValue = nativeValue;
		}

		/** The raw value the native event log stores this marker type under. */
		public int getNativeValue() {
			return nativeValue;
		}

		/** Resolves the native value back to its marker type. */
		public static ReflexMarkerType fromNative(final int value) {
			for (ReflexMarkerType type : values()) {
				if (type.nativeValue == value) {
					return type;
				}
			}
			throw new IllegalArgumentException("unknown reflex marker type " + value);
		}
	}

	/**
	 * One reflex-marker event as the native log recorded it: the marker type and the
	 * Streamline frame index (the retained frame token) the marker was emitted under.
	 */
	final class ReflexMarkerEvent {
		private final ReflexMarkerType type;
		private final int frameIndex;

		public ReflexMarkerEvent(final ReflexMarkerType type, final int frameIndex) {
			this.type = type;
			this.frameIndex = frameIndex;
		}

		public ReflexMarkerType getType() {
			return type;
		}

		public int getFrameIndex() {
			return frameIndex;
		}

		@Override
		public boolean equals(final Object other) {
			return other instanceof ReflexMarkerEvent event
				&& event.type == type
				&& event.frameIndex == frameIndex;
		}

		@Override
		public int hashCode() {
			return 31 * type.hashCode() + frameIndex;
		}

		@Override
		public String toString() {
			return "ReflexMarkerEvent{" + type + " frame=" + frameIndex + "}";
		}
	}

	/**
	 * The reflex-marker oracle: how many of each of the five Reflex/PCL markers the module
	 * has actually emitted (per-type cumulative counts), the total event count, and the
	 * recent event log in emission order, as reported by {@code mc_dlss_query_reflex_markers}.
	 *
	 * <p>Each event's frame index must equal the frame indexes the frame's SR/FG tags, its
	 * common constants, and its present markers recorded under: the input sample obtains the
	 * retained token the rest of the frame reuses, so equality of the events' indexes with
	 * {@link TaggedFrameIndexes} is what proves the marker surface shares the retained token
	 * identity. The per-type counts must each advance by exactly one per emitted marker and
	 * stay unchanged across refused or pre-ready calls, which is what proves the "refused
	 * sessions emit none" half of the M-12 contract.
	 */
	final class ReflexMarkerEvents {
		/** How many marker types the oracle reports counts for; must match the native enum width. */
		public static final int TYPE_COUNT = 5;

		/** The number of events the native log ring retains; the oracle never returns more. */
		public static final int LOG_CAPACITY = 16;

		private final int[] typeCounts;
		private final int eventCount;
		private final List<ReflexMarkerEvent> events;

		public ReflexMarkerEvents(final int[] typeCounts, final int eventCount, final List<ReflexMarkerEvent> events) {
			this.typeCounts = typeCounts.clone();
			this.eventCount = eventCount;
			this.events = List.copyOf(events);
		}

		/**
		 * How many of each marker type the module has actually emitted, in {@link
		 * ReflexMarkerType} order: INPUT_SAMPLE, SIMULATION_START, SIMULATION_END,
		 * RENDER_SUBMIT_START, RENDER_SUBMIT_END.
		 */
		public int[] getTypeCounts() {
			return typeCounts.clone();
		}

		/** How many marker events the module has actually emitted in total. */
		public int getEventCount() {
			return eventCount;
		}

		/** The most recent events in emission order, at most {@link #LOG_CAPACITY} of them. */
		public List<ReflexMarkerEvent> getEvents() {
			return events;
		}

		@Override
		public boolean equals(final Object other) {
			return other instanceof ReflexMarkerEvents markers
				&& markers.eventCount == eventCount
				&& java.util.Arrays.equals(markers.typeCounts, typeCounts)
				&& markers.events.equals(events);
		}

		@Override
		public int hashCode() {
			return 31 * (31 * java.util.Arrays.hashCode(typeCounts) + eventCount) + events.hashCode();
		}

		@Override
		public String toString() {
			return "ReflexMarkerEvents{" + java.util.Arrays.toString(typeCounts)
				+ " total=" + eventCount + " " + events + "}";
		}
	}

	/**
	 * The Reflex options registration the READY transition recorded: the {@code sl::ReflexMode}
	 * value (1 is {@code eLowLatency}) and how many {@code slReflexSetOptions} calls this
	 * session made.
	 */
	final class ReflexRegistration {
		private final int mode;
		private final int setOptionsCalls;

		public ReflexRegistration(final int mode, final int setOptionsCalls) {
			this.mode = mode;
			this.setOptionsCalls = setOptionsCalls;
		}

		/** The recorded {@code sl::ReflexMode} value: 1 is {@code eLowLatency}. */
		public int getMode() {
			return mode;
		}

		/** How many {@code slReflexSetOptions} calls this session made. */
		public int getSetOptionsCalls() {
			return setOptionsCalls;
		}

		@Override
		public boolean equals(final Object other) {
			return other instanceof ReflexRegistration registration
				&& registration.mode == mode
				&& registration.setOptionsCalls == setOptionsCalls;
		}

		@Override
		public int hashCode() {
			return 31 * mode + setOptionsCalls;
		}

		@Override
		public String toString() {
			return "ReflexRegistration{mode=" + mode + " calls=" + setOptionsCalls + "}";
		}
	}
}
