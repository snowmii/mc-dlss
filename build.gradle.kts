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

tasks.test {
	useJUnitPlatform()
	redirectJvmCrashDumps()
	// Streamline's runtime accepts exactly one Vulkan device per process (its plugin manager
	// refuses a second slSetVulkanInfo, and slShutdown cannot tear an initialized device down
	// reliably), and the native bridge module is unloaded with every FFM library arena. Both
	// make SL-state-dependent tests order- and JVM-lifetime-sensitive, so every test class runs
	// in its own worker: the Streamline activation tests each get a pristine runtime, and no
	// earlier class's device leaks into a later one.
	forkEvery = 1
	// Reproduces the mod's StreamlineVulkanProvider redirect inside a test worker. Production
	// points LWJGL at the staged sl.interposer.dll; the live FG rungs never did, which is the
	// one process-level difference between a session where DLSS-G's swapchain hook fires and a
	// game session where it does not. Off by default so the rungs keep their known-good shape:
	// -Pmc.dlss.vulkan-libname=<abs path to sl.interposer.dll> turns it on.
	providers.gradleProperty("mc.dlss.vulkan-libname").orNull
		?.let { systemProperty("org.lwjgl.vulkan.libname", it) }
}

val buildNativeDlss by tasks.registering(Exec::class) {
	group = "build"
	description = "Builds the workstation-local DLSS native bridge with MSVC."

	val nativeDirectory = layout.projectDirectory.dir("native")
	// Every translation unit under native/, so adding one to the module split does not also
	// mean remembering to add it here. The headers are inputs too: they carry the ABI structs
	// and the shared inline helpers, so a header-only edit still has to rebuild.
	val nativeSources = nativeDirectory.asFileTree.matching { include("**/*.cpp") }
	val nativeHeaders = nativeDirectory.asFileTree.matching { include("**/*.h") }
	val motionShader = nativeDirectory.file("mc_dlss_motion.comp")
	val velocityFillShader = nativeDirectory.file("mc_dlss_velocity_fill.comp")
	val outputDirectory = layout.buildDirectory.dir("native")
	inputs.files(nativeSources, nativeHeaders, motionShader, velocityFillShader)
	outputs.file(outputDirectory.map { it.file("mc_dlss.dll") })

	doFirst {
		val vsDevCmd = toolchainRoot(
			"mc.dlss.vs-dev-cmd", "VSDEVCMD",
			"C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/Common7/Tools/VsDevCmd.bat"
		)
		val vulkanSdk = toolchainRoot("mc.dlss.vulkan-sdk", "VULKAN_SDK", "C:/VulkanSDK/1.4.357.0")
		val ngxSdk = toolchainRoot("mc.dlss.ngx-sdk", "NGX_SDK", DEFAULT_NGX_SDK)
		val streamlineSdk = toolchainRoot("mc.dlss.streamline-sdk", "STREAMLINE_SDK", DEFAULT_STREAMLINE_SDK)
		val vulkanHeader = vulkanSdk.resolve("Include/vulkan/vulkan.h")
		val vulkanLibrary = vulkanSdk.resolve("Lib/vulkan-1.lib")
		// The DLSS 310.7.0 SDK is reference-only: its headers carry the quality-mode, preset, and
		// result vocabulary the public ABI keeps, but the SDK's static library is never linked
		// and no NGX runtime function is called.
		val ngxHeader = ngxSdk.resolve("include/nvsdk_ngx.h")
		val streamlineHeader = streamlineSdk.resolve("include/sl.h")
		val streamlineLibrary = streamlineSdk.resolve("lib/x64/sl.interposer.lib")
		val glslc = vulkanSdk.resolve("Bin/glslc.exe")

		check(vsDevCmd.isFile) { "Visual Studio 2022 Build Tools missing: $vsDevCmd" }
		check(glslc.isFile) { "Vulkan SDK 1.4.357.0 shader compiler missing: $glslc" }
		check(vulkanHeader.isFile) { "Vulkan SDK 1.4.357.0 header missing: $vulkanHeader (set VULKAN_SDK or install at C:/VulkanSDK/1.4.357.0)" }
		check(vulkanLibrary.isFile) { "Vulkan SDK 1.4.357.0 loader library missing: $vulkanLibrary" }
		check(ngxHeader.isFile) { "Pinned NVIDIA DLSS SDK 310.7.0 header (reference vocabulary only) missing: $ngxHeader" }
		check(streamlineHeader.isFile) { "Pinned Streamline 2.12.0 header missing: $streamlineHeader" }
		check(streamlineLibrary.isFile) { "Pinned Streamline 2.12.0 interposer library missing: $streamlineLibrary" }

		val outputDir = outputDirectory.get().asFile.apply { mkdirs() }
		val output = outputDir.resolve("mc_dlss.dll")
		// The motion-vector shaders are compiled to SPIR-V and emitted as C initializer lists,
		// which internal/motion.cpp #includes into constant arrays. Embedding them keeps the
		// bridge a single loadable file with no runtime search path for a shader blob beside it.
		val motionSpirV = outputDir.resolve("mc_dlss_motion.spv.h")
		val velocityFillSpirV = outputDir.resolve("mc_dlss_velocity_fill.spv.h")
		// Object directory, not object file: with more than one source, /Fo must name a
		// directory and must end in a separator, or cl.exe writes every object over the same
		// name and links only the last one. The separator is a forward slash - which MSVC
		// accepts in paths - because a trailing backslash would escape the closing quote and
		// swallow the rest of the command line into one argument.
		val objectDir = outputDir.resolve("obj").apply { mkdirs() }
		val objectDirArgument = objectDir.absolutePath.replace('\\', '/') + "/"
		// Sorted so the command line - and therefore the up-to-date check - does not depend on
		// the order the file tree happens to walk in.
		val sourceArguments = nativeSources.files.sorted().joinToString(" ") { "\"${it.absolutePath}\"" }
		commandLine(
			"cmd.exe", "/d", "/c",
			"call \"${vsDevCmd.absolutePath}\" -arch=x64 -host_arch=x64 && " +
				"\"${glslc.absolutePath}\" -O --target-env=vulkan1.2 -mfmt=c " +
				"-o \"${motionSpirV.absolutePath}\" \"${motionShader.asFile.absolutePath}\" && " +
				"\"${glslc.absolutePath}\" -O --target-env=vulkan1.2 -mfmt=c " +
				"-o \"${velocityFillSpirV.absolutePath}\" \"${velocityFillShader.asFile.absolutePath}\" && " +
				"cl.exe /nologo /std:c++17 /EHsc /LD /O2 /DNOMINMAX /Fo\"${objectDirArgument}\" " +
				// native/ first: the internal units include each other as "internal/<unit>.h"
				// and the public header as "mc_dlss.h", both relative to it.
				"/I\"${nativeDirectory.asFile.absolutePath}\" " +
				"/I\"${outputDir.absolutePath}\" " +
				"/I\"${vulkanSdk.resolve("Include").absolutePath}\" " +
				"/I\"${ngxSdk.resolve("include").absolutePath}\" " +
				"/I\"${streamlineSdk.resolve("include").absolutePath}\" " +
				sourceArguments + " " +
				"/link /OUT:\"${output.absolutePath}\" " +
				"/IMPLIB:\"${outputDir.resolve("mc_dlss.lib").absolutePath}\" " +
				"\"${vulkanLibrary.absolutePath}\" " +
				"\"${streamlineLibrary.absolutePath}\" Advapi32.lib User32.lib && " +
				"copy /Y \"${streamlineSdk.resolve("bin/x64/sl.interposer.dll").absolutePath}\" \"${outputDir.absolutePath}\\\" >nul && " +
				"copy /Y \"${streamlineSdk.resolve("bin/x64/sl.common.dll").absolutePath}\" \"${outputDir.absolutePath}\\\" >nul"
		)
	}
}

val streamlineRuntime = toolchainRoot("mc.dlss.streamline-sdk", "STREAMLINE_SDK", DEFAULT_STREAMLINE_SDK)
	.resolve("bin/x64")
val streamlineRuntimeFiles = listOf(
	"sl.interposer.dll", "sl.common.dll", "sl.dlss.dll", "sl.dlss_g.dll", "sl.reflex.dll",
	"sl.pcl.dll",
	"nvngx_dlss.dll", "nvngx_dlssg.dll", "NvLowLatencyVk.dll"
)

tasks.processResources {
	val version = version
	inputs.property("version", version)

	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}

	// Ship the native bridge under the mod's own namespace, so it resolves the same way
	// wherever the client runs from. The dev client's working directory is `run/`, which is
	// why a repository-relative path cannot be used.
	from(buildNativeDlss) {
		into("assets/mc-dlss/native") // McDlss.MOD_ID
	}
	from(streamlineRuntimeFiles.map(streamlineRuntime::resolve)) {
		into("assets/mc-dlss/native/streamline")
	}
	// Windows resolves mc_dlss.dll dependencies beside the bridge before bootstrap can provide
	// the plugin search path. Keep a colocated generated copy; proprietary binaries remain external.
	from(streamlineRuntimeFiles.map(streamlineRuntime::resolve)) {
		into("assets/mc-dlss/native")
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
}
