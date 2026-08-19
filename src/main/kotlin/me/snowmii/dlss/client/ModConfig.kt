package me.snowmii.dlss.client

import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import me.snowmii.dlss.session.SRModelPreset
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
 * File-backed user choices live on [user]. They share this type's mode and preset parsing, and
 * [fromSystemProperties] fills those two knobs from the file only when the command line left them
 * unnamed. [UserSettings.enabled] and [UserSettings.frameGeneration] apply after the session is
 * built, so a settings-screen toggle cannot suppress construction the way `-Dmc.dlss.enabled=false`
 * can.
 *
 * Parsing is pure: [from] re-reads the properties on every call, so the early Java bootstrap
 * seam can call [fromSystemProperties] before the mod entrypoint exists and agree with the
 * config the session later builds. Every invalid value is collected into [warnings], which the
 * entrypoint logs, so a mistyped knob names itself instead of failing or defaulting silently.
 */
class ModConfig(
	val enabled: Boolean,
	val qualityMode: SRMode,
	/** Preset this session runs; [SRModelPreset.M] unless one was asked for. Independent of [qualityMode]. */
	val renderPreset: SRModelPreset,
	val outputDimensions: Dimensions,
	/** Whether either output knob was named, which pins the session to [outputDimensions]. */
	val outputPinned: Boolean,
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
			outputPinned,
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
		// Every step pays the secondary sun march, so cost is flat in step count. Re-tune with
		// mc.dlss.stress-steps.
		private const val DEFAULT_STRESS_STEPS = 24
		private const val DEFAULT_STRESS_OCTAVES = 5
		private const val DEFAULT_STRESS_GODRAY_TAPS = 24

		/** File-backed user settings. JVM properties remain explicit overrides. */
		val user: UserSettings = UserSettings(configFile())

		/**
		 * The same parse as [from], callable from Java before the mod entrypoint has run. The
		 * Vulkan bootstrap seams need the native-library knob before [from] can be reached
		 * through a Kotlin object.
		 */
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
				// Naming either knob pins the session to that size; naming neither lets the output
				// size follow whatever main target the client actually renders into.
				outputPinned = properties.getProperty(OUTPUT_WIDTH_PROPERTY) != null ||
					properties.getProperty(OUTPUT_HEIGHT_PROPERTY) != null,
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
			if (modeValue.isNullOrEmpty()) {
				return SRMode.QUALITY
			}
			return parseMode(modeValue) ?: run {
				warnings += "$MODE_PROPERTY=$modeValue is invalid; using quality"
				SRMode.QUALITY
			}
		}

		/**
		 * Reads the preset override. Unset, "default", or unreadable values fall back to [SRModelPreset.M],
		 * independently of the quality mode.
		 */
		private fun readPreset(
			properties: Properties,
			warnings: MutableList<String>,
		): SRModelPreset {
			val value = properties.getProperty(PRESET_PROPERTY)?.trim()?.lowercase(Locale.ROOT)
			if (value.isNullOrEmpty() || value == "default") {
				return SRModelPreset.M
			}
			return parsePreset(value) ?: run {
				warnings += "$PRESET_PROPERTY=$value is invalid; using ${SRModelPreset.M.propertyValue}"
				SRModelPreset.M
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

		private fun configFile(): Path? = try {
			FabricLoader.getInstance().configDir.resolve("mc-dlss").resolve("config.json")
		} catch (_: RuntimeException) {
			// Plain unit tests have no Fabric game directory; persistence is unavailable there.
			null
		}
	}
}

/**
 * File-backed user settings. JVM properties remain explicit overrides.
 *
 * [enabled] and [frameGeneration] apply after the session is built. [qualityMode] and
 * [renderPreset] fill in only when those knobs were not named on the command line.
 */
class UserSettings internal constructor(private val file: Path?) {
	private var _enabled = true
	private var _qualityMode = SRMode.QUALITY
	private var _renderPreset = SRModelPreset.M
	private var _frameGeneration = false

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

	init {
		load()
	}

	/** Adds file-backed startup mode/preset without replacing launch-time overrides. */
	fun withSystemOverrides(overrides: Properties): Properties {
		val merged = Properties()
		merged.putAll(overrides)
		merged.putIfAbsent(ModConfig.MODE_PROPERTY, qualityMode.propertyValue)
		merged.putIfAbsent(ModConfig.PRESET_PROPERTY, renderPreset.propertyValue)
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
			_renderPreset = fields["renderPreset"]?.let(::parsePreset) ?: SRModelPreset.M
			_frameGeneration = jsonBoolean(fields["frameGeneration"]) == true
		} catch (_: IOException) {
			// Keep safe defaults when an older or manually edited file is invalid.
		} catch (_: RuntimeException) {
			// Keep safe defaults when an older or manually edited file is invalid.
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
				writeUserSettingsJson(
					enabled = _enabled,
					qualityMode = _qualityMode.propertyValue,
					renderPreset = _renderPreset.propertyValue,
					frameGeneration = _frameGeneration,
				),
				StandardCharsets.UTF_8,
			)
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
		} catch (_: IOException) {
			// Values remain effective for this session if persistence fails.
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
		return SRModelPreset.M
	}
	return SRModelPreset.fromPropertyValue(trimmed)
}

private fun jsonBoolean(value: String?): Boolean? =
	when (value?.trim()?.lowercase(Locale.ROOT)) {
		null -> null
		"true" -> true
		"false" -> false
		else -> null
	}

private val USER_SETTINGS_FIELD = Regex(""""(\w+)"\s*:\s*(true|false|null|"([^"\\]*)")""")

private fun writeUserSettingsJson(
	enabled: Boolean,
	qualityMode: String,
	renderPreset: String,
	frameGeneration: Boolean,
): String = buildString {
	append("{\n")
	append("  \"enabled\": ").append(enabled).append(",\n")
	append("  \"qualityMode\": \"").append(qualityMode).append("\",\n")
	append("  \"renderPreset\": \"").append(renderPreset).append("\",\n")
	append("  \"frameGeneration\": ").append(frameGeneration).append('\n')
	append("}\n")
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
