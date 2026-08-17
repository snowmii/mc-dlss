package me.snowmii.dlss.fg
import me.snowmii.streamline.Dimensions
import me.snowmii.dlss.client.RuntimeControls
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.SceneTarget
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * FG surface policy: the FG surface policy and the seams that apply it.
 *
 * DLSS-G must run on a swapchain that is recreated on every FG mode transition, presents
 * non-FIFO while FG is active, and has at least the declared back-buffer count. Minecraft owns
 * all of that machinery - the reconfigure happens in `Minecraft.renderFrame` after
 * `invalidateSurfaceConfiguration`, the present mode is chosen from the vsync read, and the
 * swapchain is created by `VulkanGpuSurface.configure` - so what this test proves is the
 * mod-owned policy object those seams delegate to: transitions invalidate exactly once, the
 * reconfigure reads vsync false without touching the stored option, and the minimum image
 * count covers the declared back buffers.
 *
 * The mixins that read the policy are thin handlers by design and are not unit tested; what is
 * proven here is the object they delegate to, plus the controls toggle that drives it.
 */
class FgSwapchainReconfigureTest {
	@Test
	fun `a mode transition invalidates the surface configuration exactly once`() {
		val policy = FgSurfacePolicy(invalidateSurfaceConfiguration = { invalidations++ })

		assertTrue(policy.setFrameGenerationActive(true), "switching on is a transition")
		assertFalse(policy.setFrameGenerationActive(true), "switching on again is not")
		assertTrue(policy.setFrameGenerationActive(false), "switching off is a transition")
		assertFalse(policy.setFrameGenerationActive(false), "switching off again is not")

		assertEquals(
			2,
			invalidations,
			"each transition must invalidate the surface configuration exactly once, " +
				"and a call that changes nothing must invalidate nothing",
		)
	}

	@Test
	fun `the reconfigure vsync read is false while FG is active and the stored value survives an FG on-off cycle`() {
		val policy = FgSurfacePolicy()

		assertEquals(true, policy.effectiveVsyncEnabled(true), "FG off reads the stored value")

		policy.setFrameGenerationActive(true)
		assertEquals(false, policy.effectiveVsyncEnabled(true), "FG on reads vsync false")
		assertEquals(false, policy.effectiveVsyncEnabled(false), "and false stays false")

		policy.setFrameGenerationActive(false)
		assertEquals(
			true,
			policy.effectiveVsyncEnabled(true),
			"the stored value must survive an FG on/off cycle untouched",
		)
	}

	@Test
	fun `the swapchain minimum image count meets the declared back buffers while FG is active`() {
		val policy = FgSurfacePolicy()

		assertEquals(3, policy.minImageCount(3), "FG off passes Minecraft's count through")
		assertEquals(5, policy.minImageCount(5), "and never raises it")

		policy.setFrameGenerationActive(true)
		assertEquals(4, policy.minImageCount(3), "2x declares four back buffers, so three is raised to four")
		assertEquals(5, policy.minImageCount(5), "a larger Minecraft count is never lowered")
	}

	@Test
	fun `the declared back buffers grow with the multiplier`() {
		val policy = FgSurfacePolicy()
		policy.setFrameGenerationActive(true)

		// One present per generated frame plus one for the rendered frame, plus the headroom that
		// keeps the presenter from starving - a fixed count paces worse the higher the multiplier.
		assertEquals(4, policy.requiredSwapchainImages, "2x presents two frames per app frame")
		policy.numFramesToGenerate = 2
		assertEquals(5, policy.requiredSwapchainImages, "3x presents three")
		assertEquals(5, policy.minImageCount(3), "and the swapchain must hold them")
		policy.numFramesToGenerate = 3
		assertEquals(6, policy.requiredSwapchainImages, "4x presents four")

		policy.setFrameGenerationActive(false)
		assertEquals(3, policy.minImageCount(3), "FG off restores Minecraft's count at any multiplier")
	}

	@Test
	fun `the default policy declares the back buffers the mod records for DLSS-G`() {
		assertEquals(
			FgSurfacePolicy.backBuffersFor(1),
			FgSurfacePolicy.DEFAULT_DECLARED_BACK_BUFFERS,
			"the swapchain policy must declare what LifecycleAdapter.configureFg records",
		)
		assertEquals(
			FgSurfacePolicy.DEFAULT_DECLARED_BACK_BUFFERS,
			FgSurfacePolicy().requiredSwapchainImages,
			"a fresh policy starts at the 2x multiplier's count",
		)
	}

	@Test
	fun `a runtime without a policy starts FG off with vanilla surface reads`() {
		val runtime = RenderRuntime(
			session = session(),
			sceneTarget = SceneTarget(
				allocate = { _, _ -> HeadlessRenderTarget() },
				release = {},
			),
			startup = { null },
		)

		assertFalse(runtime.frameGeneration.effective, "FG starts off")
		assertEquals(true, runtime.frameGeneration.effectiveVsyncEnabled(true), "the reconfigure reads the stored vsync")
		assertEquals(3, runtime.frameGeneration.minImageCount(3), "the swapchain keeps Minecraft's count")
	}

	@Test
	fun `toggling frame generation through the controls switches the policy and invalidates once per transition`() {
		val runtime = RenderRuntime(
			session = session(),
			sceneTarget = SceneTarget(
				allocate = { _, _ -> HeadlessRenderTarget() },
				release = {},
			),
			startup = { null },
			frameGeneration = FgSurfacePolicy(invalidateSurfaceConfiguration = { invalidations++ }),
		)
		val controls = RuntimeControls(runtime, announced::add)

		controls.toggleFrameGeneration()
		assertTrue(runtime.frameGeneration.effective, "the controls switch FG on")
		assertEquals(1, invalidations, "one transition invalidates exactly once")
		assertTrue(announced.last().contains("fg on"), "the readout names the new mode: ${announced.last()}")

		controls.toggleFrameGeneration()
		assertFalse(runtime.frameGeneration.effective, "the controls switch FG back off")
		assertEquals(2, invalidations, "a second transition invalidates exactly once more")
		assertTrue(announced.last().contains("fg off"), "the readout names the restored mode: ${announced.last()}")

		assertEquals(
			true,
			runtime.frameGeneration.effectiveVsyncEnabled(true),
			"the stored vsync read is back to the stored value after the cycle",
		)
	}

	private var invalidations = 0
	private val announced = mutableListOf<String>()

	private fun session() = DlssSession(
		DlssStartupConfig(
			enabled = true,
			qualityMode = SRMode.QUALITY,
			outputDimensions = Dimensions(2560, 1440),
			sdkPath = null,
			nativeLibraryPath = null,
			dataPath = null,
			warnings = emptyList(),
		),
	)

	private class HeadlessRenderTarget : RenderTarget("fake", true, GpuFormat.RGBA8_UNORM) {
		override fun createBuffers(width: Int, height: Int) {
			this.width = width
			this.height = height
		}

		override fun destroyBuffers() = Unit
	}
}
