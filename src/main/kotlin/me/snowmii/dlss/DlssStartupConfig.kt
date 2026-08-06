package me.snowmii.dlss

import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale
import java.util.Properties

data class DlssDimensions(
	val width: Int,
	val height: Int,
) {
	init {
		require(width > 0) { "width must be positive" }
		require(height > 0) { "height must be positive" }
	}

	override fun toString(): String = "${width}x$height"
}

data class DlssStartupConfig(
	val enabled: Boolean,
	val qualityMode: DlssQualityMode,
	/** Preset this session runs; the mode's own documented default unless one was asked for. */
	val renderPreset: DlssRenderPreset = qualityMode.defaultPreset,
	val outputDimensions: DlssDimensions,
	val sdkPath: Path?,
	val nativeLibraryPath: Path?,
	val dataPath: Path?,
	val warnings: List<String>,
) {
	companion object {
		const val ENABLED_PROPERTY = "mc.dlss.enabled"
		const val MODE_PROPERTY = "mc.dlss.mode"
		const val PRESET_PROPERTY = "mc.dlss.preset"
		const val OUTPUT_WIDTH_PROPERTY = "mc.dlss.output-width"
		const val OUTPUT_HEIGHT_PROPERTY = "mc.dlss.output-height"
		const val SDK_PATH_PROPERTY = "mc.dlss.sdk-path"
		const val NATIVE_LIBRARY_PROPERTY = "mc.dlss.native-library"
		const val DATA_PATH_PROPERTY = "mc.dlss.data-path"

		private const val DEFAULT_OUTPUT_WIDTH = 2560
		private const val DEFAULT_OUTPUT_HEIGHT = 1440

		fun from(properties: Properties = System.getProperties()): DlssStartupConfig {
			val warnings = mutableListOf<String>()
			val enabled = readBoolean(properties, ENABLED_PROPERTY, true, warnings)
			val modeValue = properties.getProperty(MODE_PROPERTY)?.trim()?.lowercase(Locale.ROOT)
			val qualityMode = when (modeValue) {
				null, "" -> DlssQualityMode.QUALITY
				"quality", "max-quality" -> DlssQualityMode.QUALITY
				"balanced" -> DlssQualityMode.BALANCED
				"performance", "max-performance" -> DlssQualityMode.PERFORMANCE
				"ultra-performance", "ultra-perf", "max-performance-ultra" -> DlssQualityMode.ULTRA_PERFORMANCE
				"dlaa" -> DlssQualityMode.DLAA
				else -> {
					warnings += "$MODE_PROPERTY=$modeValue is invalid; using quality"
					DlssQualityMode.QUALITY
				}
			}
			val renderPreset = readPreset(properties, qualityMode, warnings)
			val width = readPositiveInt(properties, OUTPUT_WIDTH_PROPERTY, DEFAULT_OUTPUT_WIDTH, warnings)
			val height = readPositiveInt(properties, OUTPUT_HEIGHT_PROPERTY, DEFAULT_OUTPUT_HEIGHT, warnings)

			return DlssStartupConfig(
				enabled = enabled,
				qualityMode = qualityMode,
				renderPreset = renderPreset,
				outputDimensions = DlssDimensions(width, height),
				sdkPath = readPath(properties, SDK_PATH_PROPERTY, warnings),
				nativeLibraryPath = readPath(properties, NATIVE_LIBRARY_PROPERTY, warnings),
				dataPath = readPath(properties, DATA_PATH_PROPERTY, warnings),
				warnings = warnings.toList(),
			)
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
			qualityMode: DlssQualityMode,
			warnings: MutableList<String>,
		): DlssRenderPreset {
			val value = properties.getProperty(PRESET_PROPERTY)?.trim()?.lowercase(Locale.ROOT)
			if (value.isNullOrEmpty() || value == "default") {
				return qualityMode.defaultPreset
			}
			return DlssRenderPreset.fromPropertyValue(value) ?: run {
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
