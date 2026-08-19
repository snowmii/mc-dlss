package me.snowmii.dlss.render.ui

import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every final-frame consumer reads the vanilla main target, never the transparent UI target.
 *
 * The consumers are vanilla 26.2 and run only inside a live client — GLFW window, GPU device,
 * render thread — so none of them can be invoked headlessly. This is a source-text scan. The
 * mapped 26.2 sources place the consumers as follows, in frame order:
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
 * A mixin on a final-frame consumer (Screenshot, Tracy) or a second `mainRenderTarget` routing
 * point would change captured outputs — stale HUD-less or pre-composite content, or the empty
 * transparent UI target — without failing the green suite: on-screen presentation stays
 * correct, and no headless test can run a consumer mixin.
 *
 * A consumer is reachable by targeting the class (`Screenshot`, `TracyFrameCapture`) or by
 * owning the caller seam that feeds it. `KeyboardHandler.keyPress` for the F2 grab is
 * ratcheted here; the present blit and Tracy capture in `Minecraft.renderFrame` are covered
 * by the `mainRenderTarget` routing-point ratchet. Parsing recognizes every `@Mixin` target
 * form and every injector `method` attribute regardless of formatting, so a differently
 * formatted annotation is not a blind spot.
 */
class CompositeRoutingTest {
	/** The final-frame capture consumers, by simple class name. */
	private val captureConsumers = listOf("Screenshot", "TracyFrameCapture")

	/**
	 * The caller seam that feeds the F2 grab: `KeyboardHandler.keyPress` calls
	 * `Screenshot.grab(Minecraft)`. A mixin injecting on it sits between the composite bake
	 * and the capture it feeds, with the same invisible effect as targeting the consumer
	 * class. The present blit and the Tracy capture are not listed here - a mixin on
	 * `Minecraft.renderFrame` is not necessarily a capture owner (the FG reconfigure mixin
	 * modifies a vsync read at the head of the method, before either consumer exists) - and
	 * their render-target reads are covered by the routing-point ratchet.
	 */
	private val consumerCallerSeams = mapOf(
		"KeyboardHandler" to "keyPress",
	)

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
	fun `no unowned mixin injects on a consumer caller seam`() {
		// Controls use Fabric key mappings, so no mixin needs to own KeyboardHandler.keyPress.
		val hits = mixinSources()
			.flatMap { (file, source) ->
				consumerSeamHits(source).map { (clazz, method) -> "$clazz.$method -> $file" }
			}
			.sorted()

		assertEquals(
			emptyList<String>(),
			hits,
			"the F2 grab must keep reading the vanilla main target - no new mixin may own a consumer caller seam: $hits",
		)
	}

	@Test
	fun `the mainRenderTarget routing point is unique and getter-scoped`() {
		val routingPoints = mixinSources()
			.flatMap { (file, source) ->
				injectorBodies(source)
					.filter { body -> methodNames(body).any { it.substringBefore('(') == "mainRenderTarget" } }
					.map { file }
			}
			.sorted()

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

	/**
	 * The class names a file's `@Mixin` annotations target, in every valid form: simple, FQN,
	 * and inner-class literals; `value =`; single and array forms of `targets =`; and the bare
	 * string form. Class literals contribute their simple name, string targets their last
	 * package segment.
	 */
	private fun mixinTargets(source: String): List<String> =
		Regex("""@Mixin\s*\((.*?)\)""").findAll(source)
			.flatMap { match ->
				val args = match.groupValues[1]
				Regex("""([A-Za-z_$][\w$]*)\s*\.\s*class""").findAll(args)
					.map { it.groupValues[1] } +
					Regex(""""([^"]+)"""").findAll(args)
						.map { it.groupValues[1].substringAfterLast('.') }
			}
			.toList()

	/**
	 * The `(class, method)` caller-seam hits of a file: one entry per injector whose target
	 * class is a consumer caller seam and whose `method` attribute names that seam's method.
	 */
	private fun consumerSeamHits(source: String): List<Pair<String, String>> {
		val targets = mixinTargets(source).toSet()
		return injectorBodies(source)
			.flatMap { body -> methodNames(body) }
			.flatMap { method ->
				consumerCallerSeams
					.filter { (clazz, seamMethod) -> clazz in targets && method.substringBefore('(') == seamMethod }
					.keys
					.map { it to method.substringBefore('(') }
			}
	}

	/**
	 * The bodies of every annotation with parentheses in the source, in order. Quoted
	 * parentheses (descriptors like `"drawFromBuffer(...)V"`) and nested annotations are
	 * handled, so attribute order and formatting never hide an injection.
	 */
	private fun annotationBodies(source: String): List<String> {
		val annotation = Regex("""@(\w+)\s*\(""")
		return annotation.findAll(source).mapNotNull { match ->
			val open = match.range.last + 1
			val close = closingParen(source, open)
			close?.let { source.substring(open, it) }
		}.toList()
	}

	private fun closingParen(source: String, from: Int): Int? {
		var depth = 1
		var i = from
		var inString = false
		while (i < source.length && depth > 0) {
			when (source[i]) {
				'"' -> inString = !inString
				'(' -> if (!inString) depth++
				')' -> if (!inString) depth--
				else -> Unit
			}
			i++
		}
		return if (depth == 0) i - 1 else null
	}

	/** The bodies of every injector annotation: any annotation carrying a `method` attribute. */
	private fun injectorBodies(source: String): List<String> =
		annotationBodies(source).filter { body -> Regex("""method\s*=""").containsMatchIn(body) }

	/** The method names an injector body targets, single or array form, in attribute order. */
	private fun methodNames(body: String): List<String> {
		val names = mutableListOf<String>()
		Regex("""method\s*=\s*"([^"]+)"""").findAll(body).forEach { names += it.groupValues[1] }
		Regex("""method\s*=\s*\{([^}]*)}""").findAll(body).forEach { array ->
			Regex(""""([^"]+)"""").findAll(array.groupValues[1]).forEach { names += it.groupValues[1] }
		}
		return names
	}
}
