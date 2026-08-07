package me.snowmii.dlss.readout
import me.snowmii.dlss.session.SRMode
import me.snowmii.dlss.bridge.DlssDimensions
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
			outputDimensions = DlssDimensions(2560, 1440),
			renderDimensions = DlssDimensions(1706, 960),
		)

		assertEquals(
			"""
			DLSS acceptance record (docs/sprint-acceptance.md#Required-PR-record)
			  reviewer=<reviewer>
			  candidate-commit=<reviewer>
			  gpu-driver=<reviewer>
			  minecraft-build=26.2
			  dlss-enabled=true
			  dlss-state=READY
			  quality-mode=quality
			  render-preset=k
			  output-resolution=2560x1440
			  internal-resolution=1706x960
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
			outputDimensions = DlssDimensions(2560, 1440),
			renderDimensions = null,
		)

		// A latched session has no internal resolution to report, and the reviewer has to be able
		// to tell that from a resolution that simply went unread.
		assertTrue(record.contains("internal-resolution=${AcceptanceRecord.UNAVAILABLE}"), record)
		assertTrue(record.contains("dlss-state=FALLBACK_LATCHED"), record)
	}

	@Test
	fun `unreadable minecraft build falls back to the reviewer rather than vanishing`() {
		val record = AcceptanceRecord.render(
			minecraftBuild = null,
			enabled = false,
			state = DlssSessionState.DISABLED,
			qualityMode = SRMode.BALANCED,
			renderPreset = SRMode.BALANCED.defaultPreset,
			outputDimensions = DlssDimensions(1920, 1080),
			renderDimensions = null,
		)

		assertTrue(record.contains("minecraft-build=${AcceptanceRecord.REVIEWER_SUPPLIED}"), record)
	}

	@Test
	fun `every field the document requires is present as a line`() {
		val record = AcceptanceRecord.render(
			minecraftBuild = "26.2",
			enabled = true,
			state = DlssSessionState.READY,
			qualityMode = SRMode.BALANCED,
			renderPreset = SRMode.BALANCED.defaultPreset,
			outputDimensions = DlssDimensions(2560, 1440),
			renderDimensions = DlssDimensions(1487, 836),
		)

		// docs/sprint-acceptance.md#Required-PR-record, minus the checklist results and overall
		// result, which the scaffold carries rather than the record.
		listOf(
			"reviewer",
			"candidate-commit",
			"gpu-driver",
			"minecraft-build",
			"quality-mode",
			"output-resolution",
			"internal-resolution",
		).forEach { field ->
			assertTrue(record.contains("\n  $field="), "missing field $field in:\n$record")
		}
	}
}
