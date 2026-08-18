package me.snowmii.dlss.readout

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Keeps the live FG checklist complete (`docs/sprint-acceptance.md`).
 *
 * Each requirement must remain named in the checklist; otherwise no reviewer can close it and
 * a clean automated run can miss the omission.
 *
 * Tests assert phrase presence rather than exact prose, so the procedure can be clarified
 * without breaking this guard. A failure names the missing phrase.
 */
class AcceptanceChecklistTest {
	companion object {
		/** The checklist under review; newlines normalized like the native-source ratchets. */
		val checklist: String by lazy {
			Files.readString(Path.of("docs", "sprint-acceptance.md")).replace("\r\n", "\n")
		}
	}

	private fun assertCovers(group: String, phrases: List<String>) {
		for (phrase in phrases) {
			assertTrue(checklist.contains(phrase), "$group coverage lost: checklist no longer mentions \"$phrase\"")
		}
	}

	@Test
	fun `checklist covers AC-2 doubling and pacing proof`() {
		// 2x displayed rate at equal spacing, with an interpolated frame indistinguishable from a
		// rendered frame within fixed artifact limits, no pink overlay, paced by FrameView
		// MsBetweenDisplayChange.
		assertCovers(
			"AC-2",
			listOf("2x", "FrameView", "MsBetweenDisplayChange", "artifact limits", "pink", "indistinguishable"),
		)
	}

	@Test
	fun `checklist covers AC-3 world motion and static terrain`() {
		// Dynamic world objects interpolate without ghosting; terrain and static geometry
		// stay correct, and the first-person hand/item stays out of the world motion surface.
		assertCovers(
			"AC-3",
			listOf("mobs", "terrain", "static geometry", "ghosting", "hand/item is excluded"),
		)
	}

	@Test
	fun `checklist covers AC-4 UI split`() {
		// Hand/item, chat, hotbar, tooltips, debug screen, and vignette stay sharp overlays
		// while the world interpolates; HUD-less excludes them, UI-alpha includes them, and the
		// composite is the presentation source.
		assertCovers(
			"AC-4",
			listOf("hotbar", "tooltip", "debug screen", "vignette", "HUD-less", "UI-alpha", "composite"),
		)
	}

	@Test
	fun `checklist covers AC-5 pacing latency and vsync`() {
		// Smooth pacing on a non-FIFO present mode, non-zero PC latency, with vsync gated
		// on support and its stored value surviving an FG cycle unchanged.
		assertCovers(
			"AC-5",
			listOf("non-FIFO", "PC latency", "vsync", "stored"),
		)
	}

	@Test
	fun `checklist covers AC-6 disable paths`() {
		// FG off restores SR-only with the split active; FG is off during loading, menus,
		// and fullscreen/windowed transitions and recovers, and whole-mod disable restores
		// vanilla rendering.
		assertCovers(
			"AC-6",
			listOf("loading", "menu", "fullscreen", "vanilla", "mc.dlss.enabled=false"),
		)
	}

	@Test
	fun `checklist documents the FG control and diagnostics`() {
		// The witness procedure is only executable with the F10 toggle and the fg=presented,
		// status, and fence readout documented: without them the checklist asks the reviewer to
		// observe what the session never shows.
		assertCovers(
			"FG diagnostics",
			listOf("F10", "fg=presented", "status", "fence", "F3"),
		)
	}

	@Test
	fun `checklist names every required acceptance-record field`() {
		// Reviewer, candidate commit, Minecraft build, GPU/driver, Streamline version and
		// plugin set, resolutions, FG multiplier, every checklist result, and the overall result
		// — the record fields the audit entry must carry.
		assertCovers(
			"AC-7 record fields",
			listOf(
				"reviewer",
				"candidate commit",
				"Minecraft build",
				"GPU and driver",
				"Streamline version",
				"plugin set",
				"FG multiplier",
				"every checklist item",
				"overall",
			),
		)
	}

	@Test
	fun `checklist names every emitted acceptance-record field`() {
		// The record block is emitted as name=value lines, and the reviewer must be able to match
		// each log line to its field without reading the source: the checklist names every
		// emitted field and the order it emits them in.
		assertCovers(
			"emitted record fields",
			listOf(
				"reviewer",
				"candidate-commit",
				"gpu-driver",
				"streamline-version",
				"streamline-plugins",
				"minecraft-build",
				"dlss-enabled",
				"dlss-state",
				"quality-mode",
				"render-preset",
				"output-resolution",
				"internal-resolution",
				"fg-multiplier",
				"checklist-result",
				"overall-result",
			),
		)
	}

	@Test
	fun `checklist names the exact FG latch diagnostic and its hex-decimal distinction`() {
		// The latch line is the one exact diagnostic a reviewer matches in the log; the status
		// word is 0x-prefixed hex there but plain decimal on the frame-rate line, and a reviewer
		// reading the wrong base misreads which status latched.
		assertCovers(
			"FG latch diagnostic",
			listOf(
				"Frame generation latched off: slDLSSGGetState",
				"status=0x",
				"(eDLSSGStatusOk=0)",
				"eOff options retained",
				"re-arm refused",
				"hex",
				"decimal",
			),
		)
	}

	@Test
	fun `checklist retains the SR checklist`() {
		// The FG checklist is additional items in the same pass/fail list: the SR coverage this
		// document already carried must survive the FG addition.
		assertCovers(
			"SR retention",
			listOf("Super Resolution", "internal scene resolution", "visibly upscaled", "native rendering"),
		)
	}
}
