package me.snowmii.dlss

import org.junit.jupiter.api.Tag

/**
 * Marks a test class that loads the native bridge, and so needs a process of its own.
 *
 * Streamline's runtime accepts exactly one Vulkan device per process - its plugin manager refuses a
 * second `slSetVulkanInfo`, and `slShutdown` cannot tear an initialized device down reliably - and
 * the bridge module is unloaded with every FFM library arena. Two such classes sharing a worker is
 * therefore one test's device leaking into the next, which fails as a confusing native error or,
 * worse, passes against the previous test's state.
 *
 * `build.gradle.kts` runs everything carrying this in `nativeBridgeTest`, one JVM per class, and
 * everything else in `test`, sharing one. That split is worth marking for: a fork costs about
 * fifteen seconds of Minecraft/Loom classpath loading, and the suite used to fork for every class -
 * seventy-odd of them, to execute under two seconds of tests.
 *
 * Nothing enforces the marker: a test that loads the bridge without it lands in the shared worker
 * and fails loudly there, which is the same signal a policy test would have given.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag(NativeBridge.TAG)
annotation class NativeBridge {
	companion object {
		const val TAG = "native-bridge"
	}
}
