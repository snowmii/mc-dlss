package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.FrameTimings
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.dlss.bridge.ExtensionBootstrap
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.Native
import me.snowmii.streamline.NativeApi
import me.snowmii.streamline.PresentTarget
import me.snowmii.dlss.session.DlssNativeStage
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssSessionState
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import me.snowmii.dlss.sl.SrLiveSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * M-9 slice rung: the DLSS-G per-frame 2x options record through the native/Kotlin ABI seam.
 *
 * The live scenario drives the seam itself: a fresh fork's module has no Streamline session,
 * so [NativeApi.configureFg] must answer FAIL_NotInitialized before bootstrap; after proxy
 * activation but before any configure it must answer FAIL_InvalidParameter (the stored
 * dimensions are still zero); and after a successful configure it must record through
 * slDLSSGSetOptions and answer success. The whole scenario runs in ONE test method (and
 * therefore one test fork) like the M-3 SR rungs: the close-path slShutdown is what makes the
 * fork's exit clean, and a fork that followed an unclean exit comes up with the plugin manager
 * already initialized.
 *
 * The recorded CONTENT is not observable through the ABI - slDLSSGSetOptions validates little
 * and stores the rest silently, and Streamline offers no options read-back. The exact field
 * decisions (2x, retained resources, UI recomposition, eBlockNoClientQueues, the declared
 * back-buffer count, and the configured extents/formats) are established by the contract
 * implementation, and the seam's acceptance is live: the call only answers success when
 * slDLSSGSetOptions accepts the record, and the adapter test below pins the ABI's
 * pass-through and failure latching.
 */
@NativeBridge
class FgOptionsContractTest {

	@Test
	fun `FG options record after ready session and valid dimensions and refuse before`(
		@TempDir dataPath: Path,
	) {
		// Pre-init: this fork's module has never bootstrapped, so the record has no Streamline
		// session to answer through. The check runs before the live session below and on a
		// throwaway bridge, and the module's bootstrap state is what it asserts against.
		Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				FAIL_NOT_INITIALIZED,
				bridge.configureFg(3),
				"configureFg before bootstrap must answer FAIL_NotInitialized",
			)
		}

		val instanceExtensions = ExtensionBootstrap.queryInstanceExtensions()

		// The production merge starts from Minecraft's {graphicsFamily: 1} queue map and
		// adds SL's extra graphics and compute queues; the first graphics family is
		// compute-capable on this workstation, so both merges land in the same family.
		val graphicsFamily = probeGraphicsQueueFamily()
		HeadlessVulkanFixture(
			instanceExtensions,
			{ instance, physicalDevice ->
				val extensions = mutableListOf<String>()
				ExtensionBootstrap.addDeviceExtensions(extensions, instance, physicalDevice)
				extensions
			},
			true,
			mapOf(graphicsFamily to requirementsExtras()),
		).use { fixture ->
			Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
				)
				// The fixture creates one host queue in the family, so Streamline's own queues
				// start at index 1 - right after the host's, as slSetVulkanInfo records them.
				val hostQueueCount = 1
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.activateVulkanProxies(
						fixture.instanceAddress(),
						fixture.physicalDeviceAddress(),
						fixture.deviceAddress(),
						graphicsFamily,
						hostQueueCount,
						graphicsFamily,
						hostQueueCount,
					),
					"activation must succeed against the merged queue layout",
				)

				// Zero dimensions: the session is ready but no configure has stored dimensions
				// yet, and the record reads everything sized from the stored configuration.
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.configureFg(3),
					"configureFg before any configure must answer FAIL_InvalidParameter",
				)

				val outputWidth = 2560
				val outputHeight = 1440
				// MaxQuality = 2 (NVSDK_NGX_PerfQuality_Value), which the bridge maps onto
				// sl::DLSSMode::eMaxQuality; preset K = 11 lands on the qualityPreset field.
				val dimensions = bridge.queryOptimalDimensions(outputWidth, outputHeight, 2)
				assertTrue(
					dimensions.width in 1..outputWidth &&
						dimensions.height in 1..outputHeight,
					"queried render dimensions must be in (0, output], got $dimensions",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.configure(
						outputWidth,
						outputHeight,
						dimensions.width,
						dimensions.height,
						2,
						11,
					),
					"configure must record the SL options for the stored configuration",
				)

				// The record: mode on, one generated frame, retained resources, UI
				// recomposition, queue mode, the declared back-buffer count, the render/output
				// extents, and the five formats. slDLSSGSetOptions answers eOk only when it
				// accepts the record, so the success result is the seam's observable contract.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.configureFg(3),
					"after a ready session and a stored configuration the FG options must record",
				)

				// Arm the close path: the already-activated tuple is recorded through the
				// existing initialize, so this bridge's close runs the orderly slShutdown while
				// the device is still alive instead of leaving the fork to crash at exit.
				SrLiveSession.recordActivatedSession(bridge, fixture, dataPath)
			}
		}
	}

	@Test
	fun `adapter records FG options only when ready and latches failures`() {
		val native = FakeNative()
		val outputDimensions = Dimensions(2560, 1440)
		val session = DlssSession(config(outputDimensions))
		val adapter = LifecycleAdapter(session, native)

		// Not ready yet: the record must not reach the bridge.
		assertFalse(adapter.configureFg(3), "a session that is not READY must not record FG options")
		assertEquals(0, native.configureFgCalls)

		// Ready: initialize arms the session, and the record passes the back-buffer count
		// through to the bridge.
		assertTrue(
			adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")) != null,
			"initialize must bring the session to READY",
		)
		assertTrue(adapter.configureFg(3), "a READY session must record the FG options")
		assertEquals(listOf(3), native.configureFgValues)

		// A refused record latches the session under the configure stage, exactly like any
		// other native stage.
		native.configureFgResult = NativeApi.SUCCESS_RESULT + 1
		assertFalse(adapter.configureFg(4), "a refused FG record must latch the session")
		assertEquals(DlssSessionState.FALLBACK_LATCHED, session.state)
		assertEquals(DlssNativeStage.CONFIGURE, session.failure?.stage)
	}

	/** The summed extra graphics + compute queues the loaded SL features require. */
	private fun requirementsExtras(): Int {
		val requirements = Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
			)
			bridge.queryQueueRequirements()
		}
		return requirements.graphicsQueues + requirements.computeQueues
	}

	/**
	 * The family the fixture creates its queues in, discovered with a throwaway default fixture
	 * so the augmented fixture can be built with the extras keyed by the right family.
	 */
	private fun probeGraphicsQueueFamily(): Int =
		HeadlessVulkanFixture().use { it.graphicsQueueFamilyIndex() }

	private fun config(outputDimensions: Dimensions) = DlssStartupConfig(
		enabled = true,
		qualityMode = SRMode.QUALITY,
		outputDimensions = outputDimensions,
		sdkPath = null,
		nativeLibraryPath = null,
		dataPath = null,
		warnings = emptyList(),
	)

	/**
	 * Records the FG-option seam and answers the three calls [LifecycleAdapter.initialize]
	 * drives; everything else is a call this test never makes.
	 */
	private class FakeNative : NativeApi {
		var configureFgResult = NativeApi.SUCCESS_RESULT
		var configureFgCalls = 0
		val configureFgValues = mutableListOf<Int>()

		override fun configureFg(numBackBuffers: Int): Int {
			configureFgCalls++
			configureFgValues += numBackBuffers
			return configureFgResult
		}

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = NativeApi.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int) =
			Dimensions(1280, 720)

		override fun configure(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = NativeApi.SUCCESS_RESULT

		override fun acquireImages(): EvaluationImages = error("unexpected acquireImages")
		override fun releaseImages(): Int = error("unexpected releaseImages")
		override fun waitDeviceIdle(): Int = error("unexpected waitDeviceIdle")
		override fun frameTimings(): FrameTimings? = error("unexpected frameTimings")
		override fun writeMotion(request: MotionRequest): Int = error("unexpected writeMotion")
		override fun presentOutput(target: PresentTarget): Int = error("unexpected presentOutput")
		override fun evaluate(request: EvaluationRequest): Int = error("unexpected evaluate")
	}

	private companion object {
		/** NVSDK_NGX_Result_FAIL_NotInitialized = NVSDK_NGX_Result_Fail | 7 (0xBAD00000 | 7). */
		const val FAIL_NOT_INITIALIZED = 0xBAD00007.toInt()

		/** NVSDK_NGX_Result_FAIL_InvalidParameter = NVSDK_NGX_Result_Fail | 5 (0xBAD00000 | 5). */
		const val FAIL_INVALID_PARAMETER = 0xBAD00005.toInt()
	}
}
