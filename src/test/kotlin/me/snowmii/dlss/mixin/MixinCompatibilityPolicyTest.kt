package me.snowmii.dlss.mixin

import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Injections coexist with other mods; exclusive injectors do not. `@Overwrite` and `@Redirect`
 * are both banned: a second mod redirecting the same call site is a hard launch failure for
 * whichever mixin loses the tie.
 *
 * Use `@Inject`, or MixinExtras `@WrapOperation` / `@ModifyExpressionValue` when a value or call
 * has to change: those compose, so two mods wrapping one call site nest instead of colliding.
 *
 * This is a source-text scan: an exclusive injector crashes only when another mod claims the
 * same seam, which a green single-mod suite never sees.
 */
class MixinCompatibilityPolicyTest {
	private val mixinDir = Path.of("src/main/java/me/snowmii/dlss/mixin")

	@Test
	fun `no mixin overwrites a vanilla method`() {
		val offenders = mixinSources().filter { (_, source) -> source.contains("@Overwrite") }.map { it.first }
		assertTrue(offenders.isEmpty(), "@Overwrite is banned - use @Inject: $offenders")
	}

	@Test
	fun `no mixin claims a call site exclusively`() {
		val redirecting = mixinSources()
			.filter { (_, source) -> Regex("""^\s*@Redirect\(""", RegexOption.MULTILINE).containsMatchIn(source) }
			.map { it.first }
			.toSet()

		assertEquals(
			emptySet<String>(),
			redirecting,
			"@Redirect is banned - prefer @Inject, or MixinExtras @WrapOperation / " +
				"@ModifyExpressionValue when a value or call must change",
		)
	}

	private fun mixinSources(): List<Pair<String, String>> {
		val files = mixinDir.listDirectoryEntries("*.java")
		assertTrue(files.isNotEmpty(), "no mixin sources found under $mixinDir")
		return files.map { it.name to it.readText() }
	}
}
