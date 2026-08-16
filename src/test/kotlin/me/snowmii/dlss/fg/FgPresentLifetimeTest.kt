package me.snowmii.dlss.fg

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.bridge.DlssEvaluationImages
import me.snowmii.dlss.bridge.DlssFrameTimings
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.ExtensionBootstrap
import me.snowmii.dlss.bridge.FgTagRequest
import me.snowmii.dlss.bridge.FillVelocityRequest
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.MotionRequest
import me.snowmii.dlss.bridge.Native
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.PresentTarget
import me.snowmii.dlss.bridge.SrTagRequest
import me.snowmii.dlss.bridge.VulkanContext
import me.snowmii.dlss.mrt.MotionVectorRoute
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitter
import me.snowmii.dlss.render.DlssJitterOffset
import me.snowmii.dlss.render.FgFrameInputs
import me.snowmii.dlss.render.FrameEvaluation
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.SceneResources
import me.snowmii.dlss.render.SceneTarget
import me.snowmii.dlss.session.DlssNativeStage
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssSessionState
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import me.snowmii.dlss.NativeBridge
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK12
import org.lwjgl.vulkan.VkApplicationInfo
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkDeviceCreateInfo
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkSemaphoreCreateInfo
import org.lwjgl.vulkan.VkSemaphoreSignalInfo
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo

/**
 * M-11 present-lifetime rung: while FG is active, the frame's start waits for the previously
 * presented frame's DLSS-G input processing to complete - the eBlockNoClientQueues fence
 * discipline - BEFORE the frame rewrites any FG-tagged input, and that wait degrades through
 * the session state like every other native stage.
 *
 * The production seam is [RenderRuntime.beginWorldPhase]: the world phase renders into the
 * scene depth and the split renders into the HUD-less and UI targets after it returns, so the
 * wait has to run there - the earliest point the mod can block before rewriting the tagged
 * inputs - and the native bridge reads `DLSSGState::inputsProcessingCompletionFence` via
 * `slDLSSGGetState` and waits on the Vulkan device. The native refusal gates (no ready
 * session, no recorded DLSS-G options) and the null-fence no-op are the bridge's contract;
 * the headless fixture that proves them against a live Streamline session is a later slice.
 * What this rung proves is the production wiring - the wait runs once per FG-active frame
 * before any recording, never on an FG-off frame, the one refusal production can legitimately
 * hit - no options recorded yet, on the first FG frame - is benign, a genuine failure latches
 * the session to vanilla, and the native entry refuses before any Streamline session exists -
 * plus the wait's value semantics, proven live through the native wait oracle against a real
 * timeline semaphore: the wait blocks until the reported value is reached, so a regression
 * that treated the semaphore as a VkFence or ignored the value fails the proof.
 */
@NativeBridge
class FgPresentLifetimeTest {

	@Test
	fun `an FG-active frame waits for the previous frame's input processing before its own rewrites`() {
		val calls = RecordingNative()
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy)

		policy.setFrameGenerationActive(true)

		// Frame A: the wait runs at the frame's start, before the first rewrite the recording
		// owns (the motion pass), and the frame hands off when it presents.
		assertTrue(
			harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS) != null,
			"an FG-active eligible frame must route to the scene target",
		)
		harness.runtime.endWorldPhase()
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION, MotionVectorRoute.CAMERA_ONLY),
			"the FG frame must record and hand off",
		)

		// Frame B: the wait must run again at the new frame's start - after frame A handed
		// off and before frame B's first rewrite - because frame B is about to overwrite the
		// inputs frame A's present is still processing.
		harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		harness.runtime.endWorldPhase()
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION, MotionVectorRoute.CAMERA_ONLY),
			"the second FG frame must record and hand off",
		)

		assertEquals(
			listOf(
				"waitFgInputs", "writeMotion", "configureFg", "fgTag", "srTag", "evaluate", "present", "fgTag", "handoff",
				"waitFgInputs", "writeMotion", "configureFg", "fgTag", "srTag", "evaluate", "present", "fgTag", "handoff",
			),
			calls.order,
			"each FG-active frame must wait for the previous frame's input processing at its " +
				"start - after the previous handoff, before its own first rewrite - and the wait " +
				"must not disturb the composed recording",
		)
		assertEquals(2, calls.waits, "two FG-active frames must wait exactly once each")
	}

	@Test
	fun `an FG-off frame makes no input-processing wait`() {
		val calls = RecordingNative()
		val harness = harness(calls, FgSurfacePolicy())

		harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		harness.runtime.endWorldPhase()
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION, MotionVectorRoute.CAMERA_ONLY),
			"an FG-off frame must still record the SR frame",
		)

		assertEquals(
			listOf("writeMotion", "srTag", "evaluate", "present"),
			calls.order,
			"an FG-off frame must make no wait and no FG calls at all",
		)
		assertEquals(0, calls.waits)
	}

	@Test
	fun `the options-less refusal on the first FG frame is benign`() {
		val calls = RecordingNative(waitResult = FAIL_INVALID_PARAMETER)
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy)

		policy.setFrameGenerationActive(true)

		// The first FG frame's start runs before any configureFg: no DLSS-G options have
		// recorded yet, the bridge refuses the wait, and there is nothing to wait for because
		// no frame has been presented through DLSS-G. The refusal must not latch the session -
		// that would kill FG on its first frame.
		assertTrue(
			harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS) != null,
			"a refused wait on the first FG frame must not abort the frame",
		)
		assertEquals(DlssSessionState.READY, harness.session.state, "the options-less refusal must not latch the session")
		assertNull(harness.session.failure, "the options-less refusal must not record a failure")
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION, MotionVectorRoute.CAMERA_ONLY),
			"the first FG frame must still record and hand off",
		)
		assertEquals(1, calls.waits, "the wait must have been asked exactly once")
	}

	@Test
	fun `a failed input-processing wait latches the session to vanilla fallback`() {
		val calls = RecordingNative(waitResult = FAIL)
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy)

		policy.setFrameGenerationActive(true)

		assertNull(
			harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS),
			"a genuine wait failure must degrade the frame to the vanilla main target",
		)
		assertEquals(DlssSessionState.FALLBACK_LATCHED, harness.session.state)
		assertEquals(DlssNativeStage.WAIT_FG_INPUTS, harness.session.failure?.stage)
		assertEquals(
			FAIL,
			harness.session.failure?.resultCode,
			"the latched failure must carry the native result the wait answered",
		)
	}

	@Test
	fun `the input wait refuses before any Streamline session exists`() {
		// Pre-init: this fork's module has never bootstrapped, so the wait seam has no
		// Streamline session to answer through - the native pre-ready refusal. The check runs
		// on a throwaway bridge and the module's bootstrap state is what it asserts against.
		Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				FAIL_NOT_INITIALIZED,
				bridge.waitFgInputsIdle(),
				"the input wait before bootstrap must answer FAIL_NotInitialized",
			)
		}
	}

	@Test
	fun `the input-processing wait blocks until the semaphore reaches the reported value`() {
		// The wait's value semantics are native behaviour no mock can reach, so the proof runs
		// the real bridge against a real headless Vulkan device and a real timeline semaphore:
		// the wait is asked for a value the semaphore has not reached, and must stay blocked
		// while the semaphore is below it - including after a lower signal - and complete only
		// once the semaphore reaches the reported value. A wait that ignored the reported
		// value (waiting for zero, say) or treated the semaphore as a VkFence - which
		// vkWaitForFences cannot wait on - would answer the first probe immediately and fail
		// the proof.
		Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			HeadlessVulkanFixture().use { fixture ->
				val device = fixture.deviceAddress()
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.waitFgInputsValue(device, 0L, 1L),
					"a null semaphore must refuse before any wait",
				)

				val semaphore = createTimelineSemaphore(fixture)
				try {
					// The reported value the plugin's state would carry for the previously
					// presented frame; the semaphore starts at zero, below it.
					val reportedValue = 7L
					val result = AtomicInteger(NativeApi.SUCCESS_RESULT)
					val waiter = thread(name = "fg-inputs-wait") {
						result.set(bridge.waitFgInputsValue(device, semaphore, reportedValue))
					}
					// Probe 1: while the semaphore still reports a value below the one the wait
					// names, a value-aware wait must not answer.
					waiter.join(WAIT_PROBE_MS)
					assertTrue(
						waiter.isAlive,
						"the wait must block while the semaphore is below the reported value",
					)
					// A lower signal must not release the wait either: only the reported value
					// is the completion of the previous frame's input processing.
					signalTimelineSemaphore(fixture, semaphore, reportedValue - 3)
					waiter.join(WAIT_PROBE_MS)
					assertTrue(
						waiter.isAlive,
						"a lower signal must not satisfy the wait for the reported value",
					)
					// Releasing the wait is what the plugin's input processing does when it
					// completes: signaling the semaphore to the reported value.
					signalTimelineSemaphore(fixture, semaphore, reportedValue)
					waiter.join(WAIT_COMPLETE_MS)
					assertFalse(
						waiter.isAlive,
						"the wait must complete once the semaphore reaches the reported value",
					)
					assertEquals(
						NativeApi.SUCCESS_RESULT,
						result.get(),
						"the wait must answer success once the reported value is reached",
					)
				} finally {
					destroySemaphore(fixture, semaphore)
				}
			}
		}
	}

	/**
	 * Builds the production present-lifetime seam over a recording fake: a READY session
	 * through the real [LifecycleAdapter], the runtime's beginWorldPhase wired to the
	 * adapter's wait exactly like [RenderRuntime.forMinecraft], and the composed frame
	 * evaluation over the same adapter.
	 */
	private fun harness(
		calls: RecordingNative,
		policy: FgSurfacePolicy,
	): Harness {
		val session = DlssSession(
			DlssStartupConfig(
				enabled = true,
				qualityMode = SRMode.QUALITY,
				outputDimensions = OUTPUT_DIMENSIONS,
				sdkPath = null,
				nativeLibraryPath = null,
				dataPath = null,
				warnings = emptyList(),
			),
		)
		val adapter = LifecycleAdapter(session, calls)
		adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data"))
		val counters = Counters()
		val context = VulkanContext.fromNativeHandles(
			1L,
			2L,
			3L,
			4L,
			commandBufferSource = {
				counters.buffers++
				fakeCommandBuffer()
			},
			commandBufferSink = { counters.submits++ },
		)
		val evaluation = FrameEvaluation(
			adapter,
			{ context },
			frameGeneration = policy,
			fgInputs = { fgInputs() },
		)
		val runtime = RenderRuntime(
			session = session,
			sceneTarget = SceneTarget(
				allocate = { width, height -> FakeTarget(width, height) },
				release = {},
			),
			startup = { RENDER_DIMENSIONS },
			frameEvaluation = evaluation,
			frameGeneration = policy,
			bridge = adapter,
		)
		return Harness(runtime, session, evaluation)
	}

	/** A [VkCommandBuffer] instance whose address() answers without any Vulkan device. */
	private fun fakeCommandBuffer(): VkCommandBuffer {
		val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
		unsafeField.isAccessible = true
		val unsafe = unsafeField.get(null) as sun.misc.Unsafe
		return unsafe.allocateInstance(VkCommandBuffer::class.java) as VkCommandBuffer
	}

	/**
	 * Rebuilds the LWJGL wrapper objects over the fixture's live handles. The fixture exposes
	 * addresses only, and the VK12 calls below route through the wrapper's device function
	 * table, so the wrappers are built with the same create-info shape the fixture's own
	 * device creation used (apiVersion 1.2) - that is what makes the VK12 command pointers
	 * (vkCreateSemaphore, vkSignalSemaphore) resolve. Same object construction the fixture
	 * performs in its constructor, on the same live handles.
	 */
	private fun HeadlessVulkanFixture.lwjglDevice(): VkDevice {
		MemoryStack.stackPush().use { stack ->
			val appInfo = VkApplicationInfo.calloc(stack)
				.`sType$Default`()
				.apiVersion(VK12.VK_API_VERSION_1_2)
			val instanceInfo = VkInstanceCreateInfo.calloc(stack)
				.`sType$Default`()
				.pApplicationInfo(appInfo)
			val instance = VkInstance(instanceAddress(), instanceInfo)
			val physicalDevice = VkPhysicalDevice(physicalDeviceAddress(), instance)
			return VkDevice(deviceAddress(), physicalDevice, VkDeviceCreateInfo.calloc(stack))
		}
	}

	/** Creates a Vulkan timeline semaphore at value zero on the fixture's device. */
	private fun createTimelineSemaphore(fixture: HeadlessVulkanFixture): Long {
		val device = fixture.lwjglDevice()
		MemoryStack.stackPush().use { stack ->
			val typeInfo = VkSemaphoreTypeCreateInfo.calloc(stack)
				.`sType$Default`()
				.semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE)
				.initialValue(0L)
			val createInfo = VkSemaphoreCreateInfo.calloc(stack)
				.`sType$Default`()
				.pNext(typeInfo.address())
				.flags(0)
			val handle = stack.callocLong(1)
			checkVk(VK12.vkCreateSemaphore(device, createInfo, null, handle), "vkCreateSemaphore")
			return handle.get(0)
		}
	}

	/** Host-signals the timeline semaphore to `value`, like the plugin does at completion. */
	private fun signalTimelineSemaphore(fixture: HeadlessVulkanFixture, semaphore: Long, value: Long) {
		val device = fixture.lwjglDevice()
		MemoryStack.stackPush().use { stack ->
			val signalInfo = VkSemaphoreSignalInfo.calloc(stack)
				.`sType$Default`()
				.semaphore(semaphore)
				.value(value)
			checkVk(VK12.vkSignalSemaphore(device, signalInfo), "vkSignalSemaphore")
		}
	}

	private fun destroySemaphore(fixture: HeadlessVulkanFixture, semaphore: Long) {
		VK10.vkDestroySemaphore(fixture.lwjglDevice(), semaphore, null)
	}

	private fun checkVk(result: Int, what: String) {
		if (result != VK10.VK_SUCCESS) {
			error("$what failed with VkResult $result")
		}
	}

	private fun scene() = SceneResources(
		color = ImageBinding(201L, 202L, 37),
		depth = ImageBinding(301L, 302L, 126),
	)

	private fun fgInputs() = FgFrameInputs(
		hudless = ImageBinding(601L, 602L, 37),
		ui = ImageBinding(701L, 702L, 37),
	)

	private fun jitter(): DlssJitterOffset = DlssJitter(RENDER_DIMENSIONS, OUTPUT_DIMENSIONS).advance()

	private fun motion() =
		DlssFrameMotion(Matrix4f(), RENDER_DIMENSIONS.width / 2f, RENDER_DIMENSIONS.height / 2f, 16.6f, false)

	private class Harness(
		val runtime: RenderRuntime,
		val session: DlssSession,
		val evaluation: FrameEvaluation,
	)

	private class Counters {
		var buffers = 0
		var submits = 0
	}

	/**
	 * Records every per-frame native call in submission order so the present-lifetime seam is
	 * assertable off the render thread; everything else is the lifecycle [LifecycleAdapter]
	 * drives to READY.
	 */
	private class RecordingNative(
		private val waitResult: Int = NativeApi.SUCCESS_RESULT,
	) : NativeApi {
		val order = mutableListOf<String>()
		var waits = 0

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = NativeApi.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int): DlssDimensions =
			RENDER_DIMENSIONS

		override fun configure(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = NativeApi.SUCCESS_RESULT

		override fun acquireImages(): DlssEvaluationImages = DlssEvaluationImages(
			motion = ImageBinding(401L, 402L, 124),
			output = ImageBinding(501L, 502L, 37),
		)

		override fun releaseImages(): Int = NativeApi.SUCCESS_RESULT

		override fun waitDeviceIdle(): Int = NativeApi.SUCCESS_RESULT

		override fun frameTimings(): DlssFrameTimings? = null

		override fun waitFgInputsIdle(): Int {
			waits++
			order += "waitFgInputs"
			return waitResult
		}

		override fun configureFg(numBackBuffers: Int): Int {
			order += "configureFg"
			return NativeApi.SUCCESS_RESULT
		}

		override fun tagFgResources(request: FgTagRequest): Int {
			order += "fgTag"
			return NativeApi.SUCCESS_RESULT
		}

		override fun presentHandoff(): Int {
			order += "handoff"
			return NativeApi.SUCCESS_RESULT
		}

		override fun tagSrResources(request: SrTagRequest): Int {
			order += "srTag"
			return NativeApi.SUCCESS_RESULT
		}

		override fun writeMotion(request: MotionRequest): Int {
			order += "writeMotion"
			return NativeApi.SUCCESS_RESULT
		}

		override fun fillVelocity(request: FillVelocityRequest): Int {
			order += "fillVelocity"
			return NativeApi.SUCCESS_RESULT
		}

		override fun presentOutput(target: PresentTarget): Int {
			order += "present"
			return NativeApi.SUCCESS_RESULT
		}

		override fun evaluate(request: EvaluationRequest): Int {
			order += "evaluate"
			return NativeApi.SUCCESS_RESULT
		}
	}

	/** Render target with no GPU buffers, so the runtime is testable off the render thread. */
	private class FakeTarget(width: Int, height: Int) : RenderTarget("fake", true, GpuFormat.RGBA8_UNORM) {
		init {
			this.width = width
			this.height = height
		}

		override fun createBuffers(width: Int, height: Int) {
			this.width = width
			this.height = height
		}

		override fun destroyBuffers() = Unit
	}

	private companion object {
		val RENDER_DIMENSIONS = DlssDimensions(1280, 720)
		val OUTPUT_DIMENSIONS = DlssDimensions(2560, 1440)

		/** The engine's output-sized main target image the frame's SR output copy records into. */
		const val DESTINATION = 900L

		/** NVSDK_NGX_Result_Fail = 0xBAD00000 | 1. */
		const val FAIL = 0xBAD00001.toInt()

		/** NVSDK_NGX_Result_FAIL_InvalidParameter = NVSDK_NGX_Result_Fail | 5 (0xBAD00000 | 5). */
		const val FAIL_INVALID_PARAMETER = 0xBAD00005.toInt()

		/** NVSDK_NGX_Result_FAIL_NotInitialized = NVSDK_NGX_Result_Fail | 7 (0xBAD00000 | 7). */
		const val FAIL_NOT_INITIALIZED = 0xBAD00007.toInt()

		/** How long a blocked wait must survive a probe before it is judged not to answer. */
		private const val WAIT_PROBE_MS = 400L

		/** How long a released wait may take to answer before it is judged stuck. */
		private const val WAIT_COMPLETE_MS = 10_000L
	}
}
