import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	id("net.fabricmc.fabric-loom")
	id("org.jetbrains.kotlin.jvm") version "2.4.10"
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

// Workstation-local toolchain roots. Every one is overridable by Gradle property first, then
// environment variable, so a second machine only needs to point these somewhere else rather
// than patch this file. The defaults are the paths the bridge was developed against.
val DEFAULT_NGX_SDK = "C:/Users/miuki/Development/NVIDIA/mc-dlss/dlss-sdk-v310.7.0/DLSS-310.7.0"
val DEFAULT_STREAMLINE_SDK = "C:/Users/miuki/Development/NVIDIA/mc-dlss/streamline-sdk-v2.12.0"

fun toolchainRoot(property: String, environment: String, default: String): File =
	providers.gradleProperty(property)
		.orElse(providers.environmentVariable(environment))
		.orElse(default)
		.get()
		.let(::file)

repositories {
	exclusiveContent {
		forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
		filter { includeGroup("maven.modrinth") }
	}
	exclusiveContent {
		forRepository {
			maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1") { name = "DevAuth" }
		}
		filter { includeGroup("me.djtheredstoner") }
	}
}

// runClient-only mods, versions in gradle.properties. Blank version = mod not in the run.
// Loose jars dropped in run/mods are loaded too, for anything without a Maven coordinate.
fun DependencyHandlerScope.runtimeMod(property: String, coordinate: (String) -> String) {
	providers.gradleProperty(property).orNull?.takeIf(String::isNotBlank)?.let {
		// `localRuntime`, not `modRuntimeOnly`: 26.2 is deobfuscated, so Loom skips its remapping
		// configurations entirely and published mods land on the run classpath unremapped.
		"localRuntime"(coordinate(it)) { isTransitive = false }
	}
}

dependencies {
	// To change the versions see the gradle.properties file
	minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
	implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")

	// Fabric API. This is technically optional, but you probably want it anyway.
	implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
	implementation("net.fabricmc:fabric-language-kotlin:${providers.gradleProperty("fabric_kotlin_version").get()}")
	implementation(project(":streamline"))
	testImplementation(testFixtures(project(":streamline")))
	// Nests the Streamline library-mod jar (id streamline-api) into the produced mod jar's
	// META-INF/jars, alongside the compile/runtime dependency above.
	include(project(":streamline"))
	testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")

	runtimeMod("modmenu_version") { "maven.modrinth:modmenu:$it" }
	// Sodium owns terrain rendering, so it is also the compatibility canary for the mixin policy
	// in AGENTS.md - it contends directly with the Vulkan chunk and pipeline seams.
	runtimeMod("sodium_version") { "maven.modrinth:sodium:$it" }
	// Real Microsoft login in dev, instead of the offline Player### profile whose session
	// requests fail with 401 on every launch.
	runtimeMod("devauth_version") { "me.djtheredstoner:DevAuth-fabric:$it" }
}

// A crashing JVM writes hs_err/replay dumps to its working directory, which for Gradle test
// workers is the project root. The DLSS native bridge can fault inside NVIDIA's own libraries,
// and `forkEvery = 1` turns one bad run into one dump per test class, so every forked JVM is
// pointed at build/jvm-crash instead of littering the repository root.
fun <T> T.redirectJvmCrashDumps() where T : Task, T : JavaForkOptions {
	val crashDirectory = layout.buildDirectory.dir("jvm-crash").get().asFile
	// %p expands to the pid, keeping concurrent workers from overwriting each other.
	jvmArgs(
		"-XX:ErrorFile=${crashDirectory.resolve("hs_err_pid%p.log")}",
		"-XX:ReplayDataFile=${crashDirectory.resolve("replay_pid%p.log")}",
	)
	// The JVM silently falls back to the working directory if the target is unwritable.
	doFirst { crashDirectory.mkdirs() }
}

// The @NativeBridge tag, mirrored from the annotation the test sources carry. Only the classes
// that load the bridge need a process of their own - see the annotation's own documentation for
// why - and a fork costs about fifteen seconds of Minecraft/Loom classpath loading. The suite used
// to run forkEvery = 1 for every class, so seventy-odd of them spent roughly eighteen minutes
// forking to execute under two seconds of tests.
val nativeBridgeTag = "native-bridge"

tasks.test {
	// One worker for every class that never touches the bridge, which is most of them.
	useJUnitPlatform { excludeTags(nativeBridgeTag) }
	redirectJvmCrashDumps()
	// The bridge is loaded with System::load and called through FFM downcalls, both restricted
	// methods the JVM warns about today and blocks in a future release.
	jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val nativeBridgeTest = tasks.register<Test>("nativeBridgeTest") {
	group = "verification"
	description = "Runs the @NativeBridge test classes, one JVM per class."
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform { includeTags(nativeBridgeTag) }
	redirectJvmCrashDumps()
	jvmArgs("--enable-native-access=ALL-UNNAMED")
	forkEvery = 1
	// Reproduces the mod's StreamlineVulkanProvider redirect inside a test worker. Production
	// points LWJGL at the staged sl.interposer.dll; the live FG rungs never did, which is the
	// one process-level difference between a session where DLSS-G's swapchain hook fires and a
	// game session where it does not. Off by default so the rungs keep their known-good shape:
	// -Pmc.dlss.vulkan-libname=<abs path to sl.interposer.dll> turns it on.
	providers.gradleProperty("mc.dlss.vulkan-libname").orNull
		?.let { systemProperty("org.lwjgl.vulkan.libname", it) }
}

// The split is only real while something enforces it. `checkLayering` decides the structural
// boundaries between the two projects in one task, so `build` fails the moment one regresses
// rather than months later when a Blaze3D import has quietly grown back into the SDK.
//
// The `sl`/NGX vocabulary boundary is not asserted here yet - src/main still carries the residue
// that assert would report, and purging it is its own change.
// The FFM surface, by the names a downcall cannot avoid spelling.
val ffmSymbol = Regex("\\b(java\\.lang\\.foreign|MemorySegment|SymbolLookup|MemoryLayout|ValueLayout|FunctionDescriptor|Arena)\\b")

val checkLayering = tasks.register("checkLayering") {
	group = "verification"
	description = "Asserts the :streamline / :mc-dlss split boundaries."

	// The engine-free classpath half runs as :streamline:checkEngineFreeClasspath - resolving
	// another project's configuration from a root task fails on that project's state lock.
	dependsOn(":streamline:checkEngineFreeClasspath")

	val streamlineMainKotlin = fileTree("streamline/src/main") { include("**/*.kt") }
	val modMainJavaOutsideMixin = fileTree("src/main/java") {
		include("**/*.java")
		exclude("me/snowmii/dlss/mixin/**")
	}
	val modMainSources = fileTree("src/main") { include("**/*.kt", "**/*.java") }

	inputs.files(streamlineMainKotlin, modMainJavaOutsideMixin, modMainSources)

	doLast {
		val violations = mutableListOf<String>()

		streamlineMainKotlin.forEach {
			violations += ":streamline main source set is Java-only, but carries Kotlin: $it"
		}
		modMainJavaOutsideMixin.forEach {
			violations += ":mc-dlss src/main/java holds only mixin/, but carries: $it"
		}
		modMainSources.forEach { file ->
			ffmSymbol.find(file.readText())?.let {
				violations += ":mc-dlss reaches the native stack through the SDK, but ${file.path} spells FFM: ${it.value}"
			}
		}

		check(violations.isEmpty()) {
			violations.joinToString("\n", prefix = "Layering boundaries violated:\n") { "  - $it" }
		}
	}
}

tasks.check { dependsOn(nativeBridgeTest, checkLayering, ":streamline:nativeTest") }

tasks.processResources {
	val version = version
	inputs.property("version", version)

	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}
}

// Windows marks every file downloaded or extracted from the internet with a `:Zone.Identifier`
// NTFS stream (mark of the web). Gradle's copy and archive tasks carry it through, so a
// downloaded resource silently pollutes the jar with bogus entries named with the sanitized
// colon (U+F03A). The streams were stripped from the tree; these exclusions keep a future
// downloaded file from re-polluting the produced jars.
tasks.withType<AbstractArchiveTask>().configureEach {
	exclude("**\uF03A*") // Gradle renders the ADS colon as U+F03A in archive entry names
	exclude("**/*Zone.Identifier")
}

tasks.processResources {
	exclude("**/*Zone.Identifier")
}

// Development-only dev-client wiring. Loom's run-config `property(...)` never reaches
// launch.cfg, so the DLSS startup properties are set on the run task's JVM directly. Every
// value is overridable, e.g. `./gradlew.bat runClient -Pmc.dlss.mode=performance`.
tasks.withType<JavaExec>().matching { it.name.startsWith("runClient") }.configureEach {
	redirectJvmCrashDumps()
	// Same restricted-method warning as the test task: System::load plus FFM downcalls.
	jvmArgs("--enable-native-access=ALL-UNNAMED")

	// DLSS only supports Minecraft's Vulkan backend. Force it for every dev-client launch so a
	// persisted OpenGL option cannot produce a misleading waiting-for-vulkan session.
	args("--graphicsBackend", "vulkan")

	// sdk-path is a compatibility input: the retired direct-NGX path searched it for its
	// feature DLL, and initialize now validates it and records only the Vulkan tuple.
	val sdkPath = toolchainRoot("mc.dlss.ngx-sdk", "NGX_SDK", DEFAULT_NGX_SDK)
		.resolve("lib/Windows_x86_64/rel")
	val dlssData = layout.buildDirectory.dir("dlss-data").get().asFile

	doFirst {
		check(args.windowed(2).any { it == listOf("--graphicsBackend", "vulkan") }) {
			"DLSS runClient requires --graphicsBackend vulkan"
		}
		dlssData.mkdirs()
	}

	systemProperty("mc.dlss.sdk-path", providers.gradleProperty("mc.dlss.sdk-path").getOrElse(sdkPath.absolutePath))
	systemProperty("mc.dlss.data-path", providers.gradleProperty("mc.dlss.data-path").getOrElse(dlssData.absolutePath))
	// Performance mode by default: the widest render/output gap, so a routing change is easiest
	// to see. Quality and balanced remain a -Pmc.dlss.mode away.
	systemProperty("mc.dlss.mode", providers.gradleProperty("mc.dlss.mode").getOrElse("performance"))
	for (name in listOf("mc.dlss.enabled", "mc.dlss.output-width", "mc.dlss.output-height")) {
		providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 25
}

kotlin {
	compilerOptions {
		jvmTarget = JvmTarget.JVM_25
	}
}

java {
	// Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
	// if it is present.
	// If you remove this line, sources will not be generated.
	withSourcesJar()

	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
}

tasks.jar {
	val projectName = project.name
	inputs.property("projectName", projectName)

	from("LICENSE") {
		rename { "${it}_$projectName" }
	}

	// The jar stages NVIDIA binaries that LICENSE deliberately does not cover, so the notices
	// naming their separate terms have to travel with them rather than staying in the repository.
	from("THIRD-PARTY-NOTICES.md") {
		rename { "${it}_$projectName" }
	}
}
