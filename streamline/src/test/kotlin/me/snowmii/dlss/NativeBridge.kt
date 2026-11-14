package me.snowmii.dlss

import org.junit.jupiter.api.Tag

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag(NativeBridge.TAG)
annotation class NativeBridge {
	companion object {
		const val TAG = "native-bridge"
	}
}
