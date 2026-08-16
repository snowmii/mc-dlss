package me.snowmii.dlss.readout
import me.snowmii.dlss.session.SRMode
import me.snowmii.streamline.Dimensions
import me.snowmii.dlss.session.DlssSessionState

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The acceptance record is what a reviewer copies into the delivery record for AC-6, so what it
 * says matters more than that it was emitted: a wrong internal resolution is worse than none.
 */
class AcceptanceRecordTest {
	@Test
	fun `record names every environment field the process can determine`() {
		val record = AcceptanceRecord.render(
			minecraftBuild = "26.2",
			enabled = true,
			state = DlssSessionState.READY,
			qualityMode = SRMode.QUALITY,
			renderPreset = SRMode.QUALITY.defaultPreset,
			outputDimensions = Dimensions(2560, 1440),
			renderDimensions = Dimensions(1706, 960),
		)

		assertEquals(
			"""
			DLSS acceptance record (docs/sprint-acceptance.md#Required-PR-record)
			  reviewer=<reviewer>
			  candidate-commit=<reviewer>
			  gpu-driver=<reviewer>
			  streamline-version=2.12.0
			  streamline-plugins=sl.dlss,sl.dlss_g,sl.reflex,sl.interposer
			  minecraft-build=26.2
			  dlss-enabled=true
			  dlss-state=READY
			  quality-mode=quality
			  render-preset=k
			  output-resolution=2560x1440
			  internal-resolution=1706x960
			  fg-multiplier=2x
			  checklist-result=<reviewer>
			  overall-result=<reviewer>
			""".trimIndent(),
			record,
		)
	}

	@Test
	fun `internal resolution reads unavailable when NGX never chose one`() {
		val record = AcceptanceRecord.render(
			minecraftBuild = "26.2",
			enabled = true,
			state = DlssSessionState.FALLBACK_LATCHED,
			qualityMode = SRMode.PERFORMANCE,
			renderPreset = SRMode.PERFORMANCE.defaultPreset,
			outputDimensions = Dimensions(2560, 1440),
			renderDimensions = null,
		)

		// A latched session has no internal resolution to report, and the reviewer has to be able
		// to tell that from a resolution that simply went unread.
		assertTrue(record.contains("internal-resolution=${AcceptanceRecord.UNAVAILABLE}"), record)
		assertTrue(record.contains("dlss-state=FALLBACK_LATCHED"), record)
	}

	@Test
	fun `record names every AC-7 field including Streamline version, plugin set, and FG multiplier`() {
		val record = AcceptanceRecord.render(
			minecraftBuild = "26.2",
			enabled = true,
			state = DlssSessionState.READY,
			qualityMode = SRMode.QUALITY,
			renderPreset = SRMode.QUALITY.defaultPreset,
			outputDimensions = Dimensions(2560, 1440),
			renderDimensions = Dimensions(1706, 960),
		)

		// AC-7 names reviewer, candidate commit, Minecraft build, GPU/driver, Streamline version
		// and plugin set, internal/output resolutions, FG multiplier, every checklist result, and
		// overall result: every field named, none left for the reviewer to guess at.
		assertTrue(record.contains("reviewer=<reviewer>"), record)
		assertTrue(record.contains("candidate-commit=<reviewer>"), record)
		assertTrue(record.contains("gpu-driver=<reviewer>"), record)
		assertTrue(record.contains("minecraft-build=26.2"), record)
		assertTrue(record.contains("streamline-version=${AcceptanceRecord.STREAMLINE_VERSION}"), record)
		assertTrue(record.contains("streamline-plugins=${AcceptanceRecord.PINNED_PLUGIN_SET}"), record)
		assertTrue(record.contains("output-resolution=2560x1440"), record)
		assertTrue(record.contains("internal-resolution=1706x960"), record)
		assertTrue(record.contains("fg-multiplier=${AcceptanceRecord.FG_MULTIPLIER}"), record)
		assertTrue(record.contains("checklist-result=<reviewer>"), record)
		assertTrue(record.contains("overall-result=<reviewer>"), record)
	}

	@Test
	fun `unreadable minecraft build falls back to the reviewer rather than vanishing`() {
		val record = AcceptanceRecord.render(
			minecraftBuild = null,
			enabled = false,
			state = DlssSessionState.DISABLED,
			qualityMode = SRMode.BALANCED,
			renderPreset = SRMode.BALANCED.defaultPreset,
			outputDimensions = Dimensions(1920, 1080),
			renderDimensions = null,
		)

		assertTrue(record.contains("minecraft-build=${AcceptanceRecord.REVIEWER_SUPPLIED}"), record)
	}

}
