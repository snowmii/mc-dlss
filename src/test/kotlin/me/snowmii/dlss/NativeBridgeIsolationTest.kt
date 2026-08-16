package me.snowmii.dlss

import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.walk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins [NativeBridge] against the test sources: a class that reaches the bridge carries the marker,
 * and a class that does not, does not.
 *
 * The marker decides which of the two test tasks a class runs in, and that is a correctness
 * boundary rather than a speed knob - see [NativeBridge]. Both directions matter. A missing marker
 * puts a bridge-loading class in the shared worker, where it inherits another test's Streamline
 * device. A stale one costs a fifteen-second fork for a class that no longer needs it, which is how
 * the whole suite ended up forking in the first place.
 *
 * Source text rather than reflection, because the thing being checked is what an author writes: the
 * failure names the file to edit, which a "wrong worker" native error never would.
 */
class NativeBridgeIsolationTest {
	@Test
	fun `a test carries the native-bridge marker exactly when it loads the bridge`() {
		assertEquals(
			testsReachingTheBridge(),
			testsCarryingTheMarker(),
			"@NativeBridge must be on exactly the test classes that reach the native bridge - add it " +
				"to the ones listed as expected, and remove it from any that no longer load the bridge",
		)
	}

	/** Test classes whose source reaches the bridge, by the entry points that load it. */
	private fun testsReachingTheBridge(): List<String> =
		testSources { source -> ENTRY_POINTS.any(source::contains) }

	/** Test classes whose source carries the marker. */
	private fun testsCarryingTheMarker(): List<String> =
		testSources { source -> source.contains("@NativeBridge") }

	/** Every test class whose source satisfies [predicate], by file name, sorted. */
	private fun testSources(predicate: (String) -> Boolean): List<String> =
		Path.of("").toAbsolutePath().resolve("src/test").walk()
			.filter { it.extension == "kt" && it.nameWithoutExtension.endsWith("Test") }
			// This test names every entry point and the marker in its own source, so it would
			// otherwise be its own first match on both sides.
			.filter { it.nameWithoutExtension != javaClass.simpleName }
			.filter { predicate(it.readText()) }
			.map { it.nameWithoutExtension }
			.sorted()
			.toList()

	private companion object {
		/**
		 * The calls that load the bridge into the worker's process, and the two test helpers that
		 * make those calls on a test's behalf - a test reaching the bridge through
		 * `HeadlessVulkanFixture` or `SrLiveSession` needs its own process exactly as much as one
		 * calling `Native.open` itself.
		 */
		private val ENTRY_POINTS =
			listOf("Native.open", "ExtensionBootstrap", "HeadlessVulkanFixture", "SrLiveSession")
	}
}
