package me.snowmii.dlss.ui

import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M-8 consumer routing — the mod-side source evidence for the invariant that every final-frame
 * consumer reads the vanilla main target and never the transparent UI target.
 *
 * The consumers are vanilla 26.2 and run only inside a live client — GLFW window, GPU device,
 * render thread — so none of them can be invoked headlessly, and simulating them (as an earlier
 * revision of this suite did with a fake "consumer read" helper) proves nothing the production
 * code actually runs. The mapped 26.2 sources place the consumers as follows, in frame order:
 *
 *  1. **Dev world icon** - `GameRenderer.render` calls `tryTakeScreenshotIfNeeded` between
 *     `renderLevel` and `guiRenderer.render`, and `takeAutoScreenshot` hands the private
 *     `mainRenderTarget` field to `Screenshot.takeScreenshot`. Both mod windows are closed at
 *     that moment — the hand window closed at the tail of `renderItemInHand`, the GUI window
 *     does not open until `GuiRenderer.render` — so the icon captures the HUD-less
 *     pre-composite main target, by design.
 *  2. **Present blit** - `Minecraft.renderFrame` blits
 *     `gameRenderer.mainRenderTarget().getColorTextureView()` to the surface after
 *     `gameRenderer.render` returns, so the GUI window has closed and the composite has baked
 *     the UI into the main target.
 *  3. **Tracy capture** - the same `renderFrame` block passes `mainRenderTarget()` to
 *     `tracyFrameCapture.capture` after the present blit.
 *  4. **F2 screenshot** - `KeyboardHandler.keyPress` calls `Screenshot.grab(Minecraft)`, which
 *     reads `minecraft.gameRenderer.mainRenderTarget()`; the press is polled between frames, so
 *     it reads the main target holding the last frame's bake.
 *
 * The mod owns the other half of the invariant, and it is already proven behaviorally: the
 * window lifecycle — the override is null outside every window, `endFrame` closes the window
 * before the composite runs, and the bake lands in the main target — is covered by
 * [UiPhaseTest], [UiHandOverlayTest], and [UiCompositeFrameWiringTest]. Those suites exercise
 * the mod-owned seams the production mixins call; this suite covers the part no headless JVM
 * can reach: that no mixin ever reaches for a consumer.
 *
 * This is a source-text policy ratchet, the sanctioned exception to
 * `docs/agents/testing.md` § "Mixins are not unit tested at all". The invisible bug it names: a
 * mixin redirecting a final-frame capture (Screenshot, Tracy) or adding a second
 * `mainRenderTarget` routing point would silently change what captured outputs contain — stale
 * HUD-less or pre-composite content, or the empty transparent UI target — while the on-screen
 * presentation stays correct. That is invisible to the green suite (no test can run a consumer
 * mixin) and to a clean single-machine run (only captured outputs differ, and nobody
 * screenshots every frame) — exactly the failure M-8's risk names.
 */
class CompositeRoutingTest {
	/** The final-frame capture consumers, by simple class name. */
	private val captureConsumers = listOf("Screenshot", "TracyFrameCapture")

	private val mixinDir = Path.of("src/main/java/me/snowmii/dlss/mixin")

	@Test
	fun `no mixin targets a final-frame capture consumer`() {
		val offenders = mixinSources()
			.filter { (_, source) -> mixinTargets(source).any { it in captureConsumers } }
			.map { it.first }

		assertTrue(
			offenders.isEmpty(),
			"screenshots and Tracy must keep reading the vanilla main target - no mixin may target a capture consumer: $offenders",
		)
	}

	@Test
	fun `the mainRenderTarget routing point is unique and getter-scoped`() {
		val routingPoints = mixinSources()
			.filter { (_, source) -> source.contains("@Inject(method = \"mainRenderTarget\"") }
			.map { it.first }

		assertEquals(
			listOf("GameRendererWorldTargetMixin.java"),
			routingPoints,
			"the phase-override routing must stay the only mainRenderTarget redirect",
		)
	}

	private fun mixinSources(): List<Pair<String, String>> {
		val files = mixinDir.listDirectoryEntries("*.java")
		assertTrue(files.isNotEmpty(), "no mixin sources found under $mixinDir")
		return files.map { it.name to it.readText() }
	}

	/** The class names a file's `@Mixin` annotation targets: simple names and `targets =` FQNs. */
	private fun mixinTargets(source: String): List<String> =
		Regex("""@Mixin\((.*?)\)""").findAll(source)
			.flatMap { match ->
				Regex("""([A-Za-z_$][\w$]*)\s*\.\s*class""")
					.findAll(match.groupValues[1])
					.map { it.groupValues[1] } +
					Regex("""targets\s*=\s*"([^"]+)"""").findAll(match.groupValues[1]).map { it.groupValues[1].substringAfterLast('.') }
			}
			.toList()
}
