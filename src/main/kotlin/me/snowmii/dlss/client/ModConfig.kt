package me.snowmii.dlss.client

import me.snowmii.dlss.DlssStartupConfig
import me.snowmii.dlss.ReflexMode
import me.snowmii.dlss.RRModelPreset
import me.snowmii.dlss.SRMode
import me.snowmii.dlss.SRModelPreset
import me.snowmii.streamline.Dimensions
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.Properties

class ModConfig(
	val enabled: Boolean,
	val qualityMode: SRMode,
	val renderPreset: SRModelPreset,
	val outputDimensions: Dimensions,
	val outputPinned: Boolean,
	val sdkPath: Path?,
	val nativeLibraryPath: Path?,
	val dataPath: Path?,
	val debugLog: Boolean = false,
	val warnings: List<String>,
) {
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
			outputPinned,
		)

	companion object {
		const val ENABLED_PROPERTY = "mc.dlss.enabled"
		const val MODE_PROPERTY = "mc.dlss.mode"
		const val PRESET_PROPERTY = "mc.dlss.preset"
		const val RR_PRESET_PROPERTY = "mc.dlss.rr-preset"
		const val REFLEX_PROPERTY = "mc.dlss.reflex"
		const val OUTPUT_WIDTH_PROPERTY = "mc.dlss.output-width"
		const val OUTPUT_HEIGHT_PROPERTY = "mc.dlss.output-height"
		const val SDK_PATH_PROPERTY = "mc.dlss.sdk-path"
		const val NATIVE_LIBRARY_PROPERTY = "mc.dlss.native-library"
		const val DATA_PATH_PROPERTY = "mc.dlss.data-path"

		const val DEBUG_LOG_PROPERTY = "mc.dlss.debug-log"


		private const val DEFAULT_OUTPUT_WIDTH = 2560
		private const val DEFAULT_OUTPUT_HEIGHT = 1440

		val user: UserSettings = UserSettings(configFile())

		@JvmStatic
		fun fromSystemProperties(): ModConfig = from(user.withSystemOverrides(System.getProperties()))

		fun from(properties: Properties = System.getProperties()): ModConfig {
			val warnings = mutableListOf<String>()
			val enabled = readBoolean(properties, ENABLED_PROPERTY, true, warnings)
			val qualityMode = readMode(properties, warnings)
			val renderPreset = readPreset(properties, warnings)
			val width = readPositiveInt(properties, OUTPUT_WIDTH_PROPERTY, DEFAULT_OUTPUT_WIDTH, warnings)
			val height = readPositiveInt(properties, OUTPUT_HEIGHT_PROPERTY, DEFAULT_OUTPUT_HEIGHT, warnings)

			return ModConfig(
				enabled = enabled,
				qualityMode = qualityMode,
				renderPreset = renderPreset,
				outputDimensions = Dimensions(width, height),
				outputPinned = properties.getProperty(OUTPUT_WIDTH_PROPERTY) != null ||
					properties.getProperty(OUTPUT_HEIGHT_PROPERTY) != null,
				sdkPath = readPath(properties, SDK_PATH_PROPERTY, warnings),
				nativeLibraryPath = readPath(properties, NATIVE_LIBRARY_PROPERTY, warnings),
				dataPath = readPath(properties, DATA_PATH_PROPERTY, warnings),
				debugLog = readBoolean(properties, DEBUG_LOG_PROPERTY, user.debugLog, warnings),
				warnings = warnings.toList(),
			)
		}

		private fun readMode(properties: Properties, warnings: MutableList<String>): SRMode {
			val modeValue = properties.getProperty(MODE_PROPERTY)?.trim()?.lowercase(Locale.ROOT)
			if (modeValue.isNullOrEmpty()) {
				return SRMode.QUALITY
			}
			return parseMode(modeValue) ?: run {
				warnings += "$MODE_PROPERTY=$modeValue is invalid; using quality"
				SRMode.QUALITY
			}
		}

		private fun readPreset(
			properties: Properties,
			warnings: MutableList<String>,
		): SRModelPreset {
			val value = properties.getProperty(PRESET_PROPERTY)?.trim()?.lowercase(Locale.ROOT)
			if (value.isNullOrEmpty() || value == "default") {
				return SRModelPreset.DEFAULT
			}
			return parsePreset(value) ?: run {
				warnings += "$PRESET_PROPERTY=$value is invalid; using ${SRModelPreset.DEFAULT.propertyValue}"
				SRModelPreset.DEFAULT
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

		private fun configFile(): Path? = try {
			FabricLoader.getInstance().configDir.resolve("mc-dlss").resolve("config.json")
		} catch (_: RuntimeException) {
			null
		}
	}
}

class UserSettings internal constructor(private val file: Path?) {
	private var _enabled = true
	private var _qualityMode = SRMode.QUALITY
	private var _renderPreset = SRModelPreset.DEFAULT
	private var _frameGeneration = false
	private var _rayReconstruction = false
	private var _rrModelPreset = RRModelPreset.DEFAULT
	private var _reflexMode = ReflexMode.ON
	private var _debugLog = false

	var enabled: Boolean
		get() = _enabled
		set(value) {
			_enabled = value
			save()
		}

	var qualityMode: SRMode
		get() = _qualityMode
		set(value) {
			_qualityMode = value
			save()
		}

	var renderPreset: SRModelPreset
		get() = _renderPreset
		set(value) {
			_renderPreset = value
			save()
		}

	var frameGeneration: Boolean
		get() = _frameGeneration
		set(value) {
			_frameGeneration = value
			save()
		}

	var rayReconstruction: Boolean
		get() = _rayReconstruction
		set(value) {
			_rayReconstruction = value
			save()
		}

	var rrModelPreset: RRModelPreset
		get() = _rrModelPreset
		set(value) {
			_rrModelPreset = value
			save()
		}

	var reflexMode: ReflexMode
		get() = _reflexMode
		set(value) {
			_reflexMode = value
			save()
		}

	var debugLog: Boolean
		get() = _debugLog
		set(value) {
			_debugLog = value
			save()
		}

	init {
		load()
	}

	fun withSystemOverrides(overrides: Properties): Properties {
		val merged = Properties()
		merged.putAll(overrides)
		merged.putIfAbsent(ModConfig.MODE_PROPERTY, qualityMode.propertyValue)
		merged.putIfAbsent(ModConfig.PRESET_PROPERTY, renderPreset.propertyValue)
		merged.putIfAbsent(ModConfig.RR_PRESET_PROPERTY, rrModelPreset.propertyValue)
		merged.putIfAbsent(ModConfig.REFLEX_PROPERTY, reflexMode.propertyValue)
		return merged
	}

	private fun load() {
		if (file == null || !Files.isRegularFile(file)) {
			return
		}
		try {
			val fields = readUserSettingsJson(Files.readString(file, StandardCharsets.UTF_8))
			_enabled = jsonBoolean(fields["enabled"]) != false
			_qualityMode = fields["qualityMode"]?.let(::parseMode) ?: SRMode.QUALITY
			_renderPreset = fields["renderPreset"]?.let(::parsePreset) ?: SRModelPreset.DEFAULT
			_frameGeneration = jsonBoolean(fields["frameGeneration"]) == true
			_rayReconstruction = jsonBoolean(fields["rayReconstruction"]) == true
			_rrModelPreset = fields["rrModelPreset"]?.let(::parseRrPreset) ?: RRModelPreset.DEFAULT
			_reflexMode = fields["reflexMode"]?.let(::parseReflexMode) ?: ReflexMode.ON
			_debugLog = jsonBoolean(fields["debugLog"]) == true
		} catch (_: IOException) {
		} catch (_: RuntimeException) {
		}
	}

	private fun save() {
		if (file == null) {
			return
		}
		val temporary = file.resolveSibling("${file.fileName}.tmp")
		try {
			Files.createDirectories(file.parent)
			Files.writeString(
				temporary,
				writeUserSettingsJson(this),
				StandardCharsets.UTF_8,
			)
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
		} catch (_: IOException) {
		} finally {
			try {
				Files.deleteIfExists(temporary)
			} catch (_: IOException) {
			}
		}
	}
}

private fun parseMode(value: String): SRMode? =
	when (value.trim().lowercase(Locale.ROOT)) {
		"quality", "max-quality" -> SRMode.QUALITY
		"balanced" -> SRMode.BALANCED
		"performance", "max-performance" -> SRMode.PERFORMANCE
		"ultra-performance", "ultra-perf", "max-performance-ultra" -> SRMode.ULTRA_PERFORMANCE
		"dlaa" -> SRMode.DLAA
		else -> null
	}

private fun parsePreset(value: String): SRModelPreset? {
	val trimmed = value.trim().lowercase(Locale.ROOT)
	if (trimmed.isEmpty() || trimmed == "default") {
		return SRModelPreset.DEFAULT
	}
	return SRModelPreset.fromPropertyValue(trimmed)
}

private fun parseRrPreset(value: String): RRModelPreset? {
	val trimmed = value.trim().lowercase(Locale.ROOT)
	if (trimmed.isEmpty() || trimmed == "default") {
		return RRModelPreset.DEFAULT
	}
	return RRModelPreset.fromPropertyValue(trimmed)
}

private fun parseReflexMode(value: String): ReflexMode? {
	val trimmed = value.trim().lowercase(Locale.ROOT)
	return ReflexMode.fromPropertyValue(trimmed)
}

private fun jsonBoolean(value: String?): Boolean? =
	when (value?.trim()?.lowercase(Locale.ROOT)) {
		null -> null
		"true" -> true
		"false" -> false
		else -> null
	}

private val USER_SETTINGS_FIELD = Regex(""""(\w+)"\s*:\s*(true|false|null|"([^"\\]*)")""")

private fun writeUserSettingsJson(settings: UserSettings): String = with(settings) {
	buildString {
		append("{\n")
		append("  \"enabled\": ").append(enabled).append(",\n")
		append("  \"qualityMode\": \"").append(qualityMode.propertyValue).append("\",\n")
		append("  \"renderPreset\": \"").append(renderPreset.propertyValue).append("\",\n")
		append("  \"frameGeneration\": ").append(frameGeneration).append(",\n")
		append("  \"rayReconstruction\": ").append(rayReconstruction).append(",\n")
		append("  \"rrModelPreset\": \"").append(rrModelPreset.propertyValue).append("\",\n")
		append("  \"reflexMode\": \"").append(reflexMode.propertyValue).append("\",\n")
		append("  \"debugLog\": ").append(debugLog).append('\n')
		append("}\n")
	}
}

private fun readUserSettingsJson(text: String): Map<String, String?> {
	val trimmed = text.trim()
	check(trimmed.startsWith('{') && trimmed.endsWith('}')) { "not a json object" }
	return USER_SETTINGS_FIELD.findAll(trimmed).associate { match ->
		val raw = match.groupValues[2]
		match.groupValues[1] to when {
			raw == "null" -> null
			raw.startsWith('"') -> match.groupValues[3]
			else -> raw
		}
	}
}
