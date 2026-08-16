package me.snowmii.dlss.config

import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.session.SRMode
import me.snowmii.dlss.session.SRModelPreset
import me.snowmii.dlss.session.DlssStartupConfig
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale
import java.util.Properties

/**
 * The mod's one configuration handle: every `-Dmc.dlss.*` knob is named here, parsed here in one
 * pass, and surfaced here as the values the rest of the mod consumes.
 *
 * The raw property namespace and its parsing live nowhere else. The two domain configs derive
 * from this single parse: [startupConfig] is the DLSS contract the session runs under (assembled
 * here, because it is the mod's front door), while the stress instrument keeps its own
 * `mc.dlss.stress-*` keys here only as raw knobs - the instrument builds [me.snowmii.dlss.pass.StressConfig]
 * itself from this handle, so a measurement scaffold never leaks into the session contract.
 *
 * Parsing is pure: [from] re-reads the properties on every call, so the early Java bootstrap
 * seam can call [fromSystemProperties] before the mod entrypoint exists and agree with the
 * config the session later builds. Every invalid value is collected into [warnings], which the
 * entrypoint logs, so a mistyped knob names itself instead of failing or defaulting silently.
 */
class ModConfig(
	val enabled: Boolean,
	val qualityMode: SRMode,
	/** Preset this session runs; the mode's own documented default unless one was asked for. */
	val renderPreset: SRModelPreset,
	val outputDimensions: DlssDimensions,
	val sdkPath: Path?,
	val nativeLibraryPath: Path?,
	val dataPath: Path?,
	/** Whether the stress measurement pass runs at all. */
	val stressEnabled: Boolean,
	/** Primary raymarch steps per pixel. The dominant cost. */
	val stressSteps: Int,
	/** FBM octaves per density sample. Multiplies the cost of every step. */
	val stressOctaves: Int,
	/** Radial godray taps. Screen-space, so its cost is independent of the march. */
	val stressGodrayTaps: Int,
	/** Scales the stress effect's contribution; 0 renders the scene unchanged but still pays for it. */
	val stressIntensity: Float,
	/** Sign applied to the reconstructed NDC y; -1 when the backend's viewport is y-flipped. */
	val stressNdcYSign: Float,
	val warnings: List<String>,
) {
	/** The session's DLSS contract config, derived from this one parse. */
	val startupConfig: DlssStartupConfig
		get() = DlssStartupConfig(
			enabled,
			qualityMode,
			renderPreset,
			outputDimensions,
			sdkPath,
			nativeLibraryPath,
			dataPath,
			warnings,
		)

	companion object {
		const val ENABLED_PROPERTY = "mc.dlss.enabled"
		const val MODE_PROPERTY = "mc.dlss.mode"
		const val PRESET_PROPERTY = "mc.dlss.preset"
		const val OUTPUT_WIDTH_PROPERTY = "mc.dlss.output-width"
		const val OUTPUT_HEIGHT_PROPERTY = "mc.dlss.output-height"
		const val SDK_PATH_PROPERTY = "mc.dlss.sdk-path"
		const val NATIVE_LIBRARY_PROPERTY = "mc.dlss.native-library"
		const val DATA_PATH_PROPERTY = "mc.dlss.data-path"

		const val STRESS_ENABLED_PROPERTY = "mc.dlss.stress"
		const val STRESS_STEPS_PROPERTY = "mc.dlss.stress-steps"
		const val STRESS_OCTAVES_PROPERTY = "mc.dlss.stress-octaves"
		const val STRESS_GODRAYS_PROPERTY = "mc.dlss.stress-godrays"
		const val STRESS_INTENSITY_PROPERTY = "mc.dlss.stress-intensity"
		const val STRESS_FLIP_Y_PROPERTY = "mc.dlss.stress-flip-y"

		private const val DEFAULT_OUTPUT_WIDTH = 2560
		private const val DEFAULT_OUTPUT_HEIGHT = 1440
		// Retuned down from 64 when the stress march stopped branching on density: every step now
		// pays the secondary sun march that used to run on dense samples only, so a step costs
		// roughly three times what an average one did and the same total load needs about a third
		// of the count. Re-tune with mc.dlss.stress-steps; the cost is flat in it now, which is
		// what makes it a usable dial.
		private const val DEFAULT_STRESS_STEPS = 24
		private const val DEFAULT_STRESS_OCTAVES = 5
		private const val DEFAULT_STRESS_GODRAY_TAPS = 24

		/**
		 * The same parse as [from], callable from Java before the mod entrypoint has run. The
		 * Vulkan bootstrap seams need the native-library knob before [from] can be reached
		 * through a Kotlin object.
		 */
		@JvmStatic
		fun fromSystemProperties(): ModConfig = from()

		fun from(properties: Properties = System.getProperties()): ModConfig {
			val warnings = mutableListOf<String>()
			val enabled = readBoolean(properties, ENABLED_PROPERTY, true, warnings)
			val qualityMode = readMode(properties, warnings)
			val renderPreset = readPreset(properties, qualityMode, warnings)
			val width = readPositiveInt(properties, OUTPUT_WIDTH_PROPERTY, DEFAULT_OUTPUT_WIDTH, warnings)
			val height = readPositiveInt(properties, OUTPUT_HEIGHT_PROPERTY, DEFAULT_OUTPUT_HEIGHT, warnings)

			return ModConfig(
				enabled = enabled,
				qualityMode = qualityMode,
				renderPreset = renderPreset,
				outputDimensions = DlssDimensions(width, height),
				sdkPath = readPath(properties, SDK_PATH_PROPERTY, warnings),
				nativeLibraryPath = readPath(properties, NATIVE_LIBRARY_PROPERTY, warnings),
				dataPath = readPath(properties, DATA_PATH_PROPERTY, warnings),
				stressEnabled = readBoolean(properties, STRESS_ENABLED_PROPERTY, false, warnings),
				stressSteps = readInt(properties, STRESS_STEPS_PROPERTY, DEFAULT_STRESS_STEPS, 1, 192),
				stressOctaves = readInt(properties, STRESS_OCTAVES_PROPERTY, DEFAULT_STRESS_OCTAVES, 1, 8),
				stressGodrayTaps = readInt(properties, STRESS_GODRAYS_PROPERTY, DEFAULT_STRESS_GODRAY_TAPS, 0, 48),
				stressIntensity = readFloat(properties, STRESS_INTENSITY_PROPERTY, 1.0f, 0.0f, 4.0f),
				stressNdcYSign = if (readBoolean(properties, STRESS_FLIP_Y_PROPERTY, false, warnings)) -1.0f else 1.0f,
				warnings = warnings.toList(),
			)
		}

		private fun readMode(properties: Properties, warnings: MutableList<String>): SRMode {
			val modeValue = properties.getProperty(MODE_PROPERTY)?.trim()?.lowercase(Locale.ROOT)
			return when (modeValue) {
				null, "" -> SRMode.QUALITY
				"quality", "max-quality" -> SRMode.QUALITY
				"balanced" -> SRMode.BALANCED
				"performance", "max-performance" -> SRMode.PERFORMANCE
				"ultra-performance", "ultra-perf", "max-performance-ultra" -> SRMode.ULTRA_PERFORMANCE
				"dlaa" -> SRMode.DLAA
				else -> {
					warnings += "$MODE_PROPERTY=$modeValue is invalid; using quality"
					SRMode.QUALITY
				}
			}
		}

		/**
		 * Reads the preset override, falling back to the mode's own default.
		 *
		 * An unreadable value degrades to that default rather than to one fixed preset, because
		 * the default is per mode: silently running Performance on K would be a quieter and worse
		 * outcome than the invalid value the reviewer typed.
		 */
		private fun readPreset(
			properties: Properties,
			qualityMode: SRMode,
			warnings: MutableList<String>,
		): SRModelPreset {
			val value = properties.getProperty(PRESET_PROPERTY)?.trim()?.lowercase(Locale.ROOT)
			if (value.isNullOrEmpty() || value == "default") {
				return qualityMode.defaultPreset
			}
			return SRModelPreset.fromPropertyValue(value) ?: run {
				warnings += "$PRESET_PROPERTY=$value is invalid; using ${qualityMode.defaultPreset.propertyValue}"
				qualityMode.defaultPreset
			}
		}

		private fun readBoolean(
			properties: Properties,
			name: String,
			default: Boolean,
			warnings: MutableList<String>,
		): Boolean {
			val value = properties.getProperty(name)?.trim()?.lowercase(Locale.ROOT) ?: return default
			return when (value) {
				"true", "1", "yes", "on" -> true
				"false", "0", "no", "off" -> false
				else -> {
					warnings += "$name=$value is invalid; using $default"
					default
				}
			}
		}

		private fun readPositiveInt(
			properties: Properties,
			name: String,
			default: Int,
			warnings: MutableList<String>,
		): Int {
			val value = properties.getProperty(name)?.trim() ?: return default
			return value.toIntOrNull()?.takeIf { it > 0 } ?: run {
				warnings += "$name=$value is invalid; using $default"
				default
			}
		}

		/** A bounded integer knob; out-of-range values clamp rather than warn, by design. */
		private fun readInt(properties: Properties, name: String, default: Int, min: Int, max: Int): Int =
			properties.getProperty(name)?.trim()?.toIntOrNull()?.coerceIn(min, max) ?: default

		/** A bounded float knob; out-of-range values clamp rather than warn, by design. */
		private fun readFloat(properties: Properties, name: String, default: Float, min: Float, max: Float): Float =
			properties.getProperty(name)?.trim()?.toFloatOrNull()?.coerceIn(min, max) ?: default

		private fun readPath(properties: Properties, name: String, warnings: MutableList<String>): Path? {
			val value = properties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
			return try {
				Path.of(value)
			} catch (_: InvalidPathException) {
				warnings += "$name=$value is invalid; ignoring path"
				null
			}
		}
	}
}
