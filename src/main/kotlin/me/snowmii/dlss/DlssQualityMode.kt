package me.snowmii.dlss

enum class DlssQualityMode(
	val ngxValue: Int,
	val propertyValue: String,
) {
	QUALITY(2, "quality"),
	BALANCED(1, "balanced"),
	PERFORMANCE(0, "performance"),
}
