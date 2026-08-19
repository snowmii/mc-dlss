package me.snowmii.dlss.render

import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssSessionState
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.StreamlineSessionTestDouble
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class EvaluationImagesTest {
	private val output = Dimensions(2560, 1440)
	private val render = Dimensions(1280, 720)

	@Test
	fun `ready session reuses and rebuilds evaluation images`() {
		val native = RecordingNative()
		val session = DlssSession(
			DlssStartupConfig(
				enabled = true,
				qualityMode = SRMode.QUALITY,
				outputDimensions = output,
				sdkPath = Path.of("sdk"),
				nativeLibraryPath = Path.of("native"),
				dataPath = Path.of("data"),
				warnings = emptyList(),
			),
		)
		val adapter = LifecycleAdapter(session, native)

		assertEquals(render, adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")))
		assertEquals(DlssSessionState.READY, session.state)

		val images = adapter.acquireImages()
		assertNotNull(images)
		assertEquals(images, adapter.acquireImages())
		assertEquals(2, native.acquireCalls)
		assertTrue(adapter.releaseImages())
		assertTrue(adapter.releaseImages())
		assertEquals(2, native.releaseCalls)

		val rebuilt = adapter.acquireImages()
		assertNotNull(rebuilt)
		assertNotEquals(images, rebuilt)
		assertTrue(adapter.releaseImages())
		assertNull(session.failure)
	}

	@Test
	fun `not ready session does not acquire evaluation images`() {
		val native = RecordingNative()
		val session = DlssSession(startupConfig())
		val adapter = LifecycleAdapter(session, native)

		assertNull(adapter.acquireImages())
		assertEquals(0, native.acquireCalls)
	}

	private fun startupConfig() = DlssStartupConfig(
		enabled = true,
		qualityMode = SRMode.QUALITY,
		outputDimensions = output,
		sdkPath = Path.of("sdk"),
		nativeLibraryPath = Path.of("native"),
		dataPath = Path.of("data"),
		warnings = emptyList(),
	)

	private class RecordingNative : StreamlineSessionTestDouble() {
		private val configuredRender = Dimensions(1280, 720)
		var acquireCalls = 0
		var releaseCalls = 0
		private var images: EvaluationImages? = null

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		) = StreamlineSession.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int) = configuredRender

		override fun configureSuperResolution(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		) = StreamlineSession.SUCCESS_RESULT

		override fun acquireImages(): EvaluationImages {
			acquireCalls++
			return images ?: EvaluationImages(
				ImageBinding(100L + acquireCalls, 200L + acquireCalls, 83),
				ImageBinding(300L + acquireCalls, 400L + acquireCalls, 37),
			).also { images = it }
		}

		override fun releaseImages(): Int {
			releaseCalls++
			images = null
			return StreamlineSession.SUCCESS_RESULT
		}
	}
}
