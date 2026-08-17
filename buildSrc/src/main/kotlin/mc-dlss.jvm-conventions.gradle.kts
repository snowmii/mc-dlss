import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// What `:mc-dlss` and `:streamline` agree on. Both compile to the same JVM, both run a suite
// split by whether a class loads the native bridge, and both produce archives that must not
// carry Windows' mark-of-the-web streams. Kept here so the two build scripts hold what is
// actually different about them.

plugins {
	java
	// The Kotlin version is pinned once, in buildSrc's own dependency on the plugin. `:streamline`
	// gets it too: its main sources are Java, but its tests and fixtures are Kotlin.
	id("org.jetbrains.kotlin.jvm")
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 25
}

extensions.configure<KotlinJvmProjectExtension> {
	compilerOptions {
		jvmTarget = JvmTarget.JVM_25
	}
}

// Windows marks every file downloaded or extracted from the internet with a `:Zone.Identifier`
// NTFS stream (mark of the web). Gradle's copy and archive tasks carry it through, so a
// downloaded resource silently pollutes the jar with bogus entries named with the sanitized
// colon (U+F03A). The streams were stripped from the tree; these exclusions keep a future
// downloaded file from re-polluting the produced jars.
tasks.withType<AbstractArchiveTask>().configureEach {
	exclude("***") // Gradle renders the ADS colon as U+F03A in archive entry names
	exclude("**/*Zone.Identifier")
}

tasks.processResources {
	exclude("**/*Zone.Identifier")
}

tasks.withType<Test>().configureEach {
	redirectJvmCrashDumps()
	// The bridge is loaded with System::load and called through FFM downcalls, both restricted
	// methods the JVM warns about today and blocks in a future release.
	jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// The @NativeBridge tag, mirrored from the annotation the test sources carry. Only the classes
// that load the bridge need a process of their own - Streamline's runtime accepts one Vulkan
// device per process - and a fork costs a fresh JVM plus classpath loading. Everything else
// shares one worker in `test`.
val nativeBridgeTag = "native-bridge"

tasks.test {
	useJUnitPlatform { excludeTags(nativeBridgeTag) }
}

val sourceSets = extensions.getByType<SourceSetContainer>()

tasks.register<Test>("nativeBridgeTest") {
	group = "verification"
	description = "Runs the @NativeBridge test classes, one JVM per class."
	testClassesDirs = sourceSets.named("test").get().output.classesDirs
	classpath = sourceSets.named("test").get().runtimeClasspath
	useJUnitPlatform { includeTags(nativeBridgeTag) }
	forkEvery = 1
}
