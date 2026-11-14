package me.snowmii.dlss

import me.snowmii.streamline.Dimensions
import java.nio.file.Path

data class DlssStartupConfig(
	val enabled: Boolean,
	val qualityMode: SRMode,
	val renderPreset: SRModelPreset = SRModelPreset.DEFAULT,
	val outputDimensions: Dimensions,
	val sdkPath: Path?,
	val nativeLibraryPath: Path?,
	val dataPath: Path?,
	val warnings: List<String>,
	val outputPinned: Boolean = false,
)

enum class SRModelPreset(
	val sdkValue: Int,
	val propertyValue: String,
	val displayName: String,
) {
	DEFAULT(0, "default", "Default"),
	K(11, "k", "Preset K"),
	L(12, "l", "Preset L"),
	M(13, "m", "Preset M"),
	;

	companion object {
		fun fromPropertyValue(value: String): SRModelPreset? =
			entries.firstOrNull { it.propertyValue == value }
	}
}

enum class RRModelPreset(
	val sdkValue: Int,
	val propertyValue: String,
	val displayName: String,
) {
	DEFAULT(0, "default", "Default"),
	D(4, "d", "Preset D"),
	E(5, "e", "Preset E"),
	F(6, "f", "Preset F"),
	;

	companion object {
		fun fromPropertyValue(value: String): RRModelPreset? =
			entries.firstOrNull { it.propertyValue == value }
	}
}

enum class SRMode(
	val sdkValue: Int,
	val propertyValue: String,
	val displayName: String,
) {
	DLAA(5, "dlaa", "DLAA"),
	QUALITY(2, "quality", "Quality"),
	BALANCED(1, "balanced", "Balanced"),
	PERFORMANCE(0, "performance", "Performance"),
	ULTRA_PERFORMANCE(3, "ultra-performance", "Ultra Performance"),
}

enum class ReflexMode(
	val sdkValue: Int,
	val propertyValue: String,
	val displayName: String,
) {
	OFF(0, "off", "Off"),
	ON(1, "on", "On"),
	BOOST(2, "boost", "On + Boost");

	companion object {
		fun fromPropertyValue(value: String): ReflexMode? =
			entries.firstOrNull { it.propertyValue.equals(value, ignoreCase = true) }
	}
}
