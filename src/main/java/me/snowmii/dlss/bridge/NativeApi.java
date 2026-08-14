package me.snowmii.dlss.bridge;

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
	 */
	int initialize(
		long vkInstance,
		long vkPhysicalDevice,
		long vkDevice,
		Path sdkPath,
		Path dataPath
	);

	DlssDimensions queryOptimalDimensions(
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
	 * Records the DLSS-G per-frame 2x options with Streamline's {@code slDLSSGSetOptions}:
	 * mode on, one generated frame per real one, retained resources while off, UI
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
	 * Returns the native-owned motion and output images for the configured dimensions,
	 * creating them on first use and reusing them while that configuration holds.
	 *
	 * <p>Reported so the caller can see what it is rendering through and drive their release.
	 * They are never passed back in for an evaluation - the bridge reaches its own images
	 * directly.
	 */
	DlssEvaluationImages acquireImages();

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
	DlssFrameTimings frameTimings();

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
	 * without it never generates. Answers {@code FAIL_NotInitialized} while the session is
	 * not ready and {@code FAIL_InvalidParameter} when no handoff armed the bracket.
	 *
	 * <p>Default-implemented for the same reason as {@link #presentHandoff()}.
	 */
	default int presentStart() {
		throw new UnsupportedOperationException("presentStart");
	}

	/**
	 * Emits the PRESENT_END Reflex marker of the armed present bracket under the frame's
	 * retained token, immediately after the queue present returned, and consumes the
	 * bracket: whether the marker succeeded or failed, the frame is consumed exactly like a
	 * successful one, so the next frame's tags obtain a fresh token under a fresh index.
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
	 * row-major view-to-clip and clip-to-view matrices and the camera's world-space position
	 * and orthonormal right/up/forward basis, exactly as {@link #evaluate(EvaluationRequest)}
	 * carried them.
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
}
