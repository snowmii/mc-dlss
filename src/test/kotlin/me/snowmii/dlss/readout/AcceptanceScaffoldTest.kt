package me.snowmii.dlss.readout
import me.snowmii.dlss.config.ModConfig
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.pass.StressConfig

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The acceptance document describes the candidate a reviewer is actually holding.
 *
 * Every criterion in this effort closes on a live human following this document, which makes it
 * the one artifact whose drift is invisible until the session it governs has already been run
 * wrong. It has drifted once already: it described toggling DLSS as a restart with a different
 * property for the whole time the runtime keys existed.
 *
 * So the things the document promises are asserted against the code that provides them - the key
 * codes, the property names, the diagnostic prefixes, and the record fields - rather than trusted
 * to stay true. The wording of the five checklist items and of the required record fields is
 * approved contract text and is asserted to still be present, not to be phrased any particular way.
 */
class AcceptanceScaffoldTest {
	private val document: String = Files.readString(Path.of("docs/sprint-acceptance.md"))

	@Test
	fun `the approved checklist items are all still there`() {
		// The contract's AC-1 through AC-5 cite this list by section. Losing or rewording an item
		// here would quietly change what the effort is accepted against.
		listOf(
			"DLSS Super Resolution reports supported and enabled, and evaluates each displayed world frame.",
			"Toggling DLSS changes internal scene resolution while window/output resolution remains fixed.",
			"World scene is visibly upscaled in the displayed path.",
			"First-person hand/item, screen effects, 3D crosshair, HUD, and GUI render after DLSS at output resolution.",
			"Disabling integration restores native rendering path.",
		).forEach { item ->
			assertTrue(document.contains(item), "the approved checklist lost an item: $item")
		}
	}

	@Test
	fun `every field AC-6 requires is still asked for`() {
		listOf(
			"reviewer identity",
			"candidate commit",
			"Minecraft build",
			"GPU and driver",
			"internal and output resolutions",
			"DLSS quality mode",
			"overall acceptance result",
		).forEach { field ->
			assertTrue(document.contains(field), "the required PR record lost a field: $field")
		}
	}

	@Test
	fun `the document names the keys the client actually binds`() {
		// Mirrors KeyboardHandlerControlsMixin. A reviewer pressing a key this document names and
		// the client does not bind gets silence, and reads it as the feature not working.
		listOf("F6", "F7", "F8", "F9").forEach { key ->
			assertTrue(document.contains("| $key |"), "the controls table lost $key")
		}
	}

	@Test
	fun `the document names the properties the code reads`() {
		listOf(
			ModConfig.ENABLED_PROPERTY,
			ModConfig.STRESS_ENABLED_PROPERTY,
			ModConfig.STRESS_STEPS_PROPERTY,
			ModConfig.STRESS_OCTAVES_PROPERTY,
			ModConfig.STRESS_GODRAYS_PROPERTY,
			ModConfig.STRESS_INTENSITY_PROPERTY,
		).forEach { property ->
			assertTrue(document.contains(property), "the document names no property $property")
		}
	}

	@Test
	fun `the document names the diagnostic lines the reviewer reads values off`() {
		// The three values no reviewer can read off the screen - the internal resolution, the route,
		// and the GPU cost of the chain - exist only on these lines.
		assertTrue(document.contains(AcceptanceRecord.HEADING.substringBefore(" (")))
		assertTrue(document.contains("DLSS first world phase"))
		assertTrue(document.contains("DLSS world frame rate"))
		assertTrue(document.contains(AcceptanceRecord.REVIEWER_SUPPLIED))
	}

	@Test
	fun `the workload bounds the document quotes are the ones the config enforces`() {
		val extreme = java.util.Properties().apply {
			setProperty(ModConfig.STRESS_STEPS_PROPERTY, "999999")
			setProperty(ModConfig.STRESS_OCTAVES_PROPERTY, "999999")
			setProperty(ModConfig.STRESS_GODRAYS_PROPERTY, "999999")
			setProperty(ModConfig.STRESS_INTENSITY_PROPERTY, "999999")
		}
		val clamped = StressConfig.from(extreme)
		val defaults = StressConfig.from(java.util.Properties())

		// A reviewer who writes down "192 steps" because this document said so, on a build that
		// clamps to something else, records a workload the frame never paid.
		assertTrue(document.contains("1-${clamped.steps}, default ${defaults.steps}"))
		assertTrue(document.contains("1-${clamped.octaves}, default ${defaults.octaves}"))
		assertTrue(document.contains("0-${clamped.godrayTaps}, default ${defaults.godrayTaps}"))
		assertTrue(document.contains("0.0-${clamped.intensity}, default ${defaults.intensity}"))
	}
}
