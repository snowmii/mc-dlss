plugins {
	`java-library`
	`java-test-fixtures`
	id("mc-dlss.jvm-conventions")
}

version = providers.gradleProperty("mod_version").get()

// The toolchain roots this project's MSVC tasks resolve, each overridable by Gradle property
// first and environment variable second (see buildSrc's toolchainRoot). The defaults are the
// paths the bridge was developed against.
val DEFAULT_NGX_SDK = "C:/Users/miuki/Development/NVIDIA/mc-dlss/dlss-sdk-v310.7.0/DLSS-310.7.0"
val DEFAULT_STREAMLINE_SDK = "C:/Users/miuki/Development/NVIDIA/mc-dlss/streamline-sdk-v2.12.0"

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.joml:joml:1.10.8")
	// Type references only: VulkanContext's command-buffer supplier/consumer are typed
	// org.lwjgl.vulkan.VkCommandBuffer, and javac needs the jar on the compile classpath. No
	// SDK static initializer touches an org.lwjgl.vulkan class - SlVulkanFeatures hardcodes its
	// feature offsets precisely to avoid pulling one - so the Streamline runtime still loads
	// before any LWJGL-Vulkan class initializes. Brings org.lwjgl:lwjgl:3.4.1 transitively.
	implementation("org.lwjgl:lwjgl-vulkan:3.4.1")

	// The relocated SDK-subject JVM suite: JUnit 5 for the Kotlin/Java test sources, and the
	// test-fixtures jar (SrLiveSession + HeadlessVulkanFixture) the shared live rungs and the
	// root suite compile against. fastutil is the fixture's internal Int2IntMap usage; junit is
	// there because SrLiveSession asserts through JUnit itself.
	testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
	// The fixture drives real LWJGL Vulkan calls (MemoryStack, VK* structs) at runtime; the root
	// suite got these natives from Minecraft's classpath, :streamline must stage them itself. Same
	// 3.4.1 coordinate the main unit's lwjgl-vulkan pins.
	testRuntimeOnly("org.lwjgl:lwjgl:3.4.1:natives-windows")
	testFixturesImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
	testFixturesImplementation("it.unimi.dsi:fastutil:8.5.15")
	// The fixture drives a real headless Vulkan context through org.lwjgl.vulkan; the main unit's
	// implementation-scoped lwjgl-vulkan does not reach the testFixtures compile classpath, so the
	// fixture declares the same coordinate itself (identical version, no new transitive surface).
	testFixturesImplementation("org.lwjgl:lwjgl-vulkan:3.4.1")
}

val buildNativeDlss = tasks.register<Exec>("buildNativeDlss") {
	group = "build"
	description = "Builds the workstation-local DLSS native bridge with MSVC."

	val nativeDirectory = layout.projectDirectory.dir("native")
	// Every translation unit under native/, so adding one to the module split does not also
	// mean remembering to add it here. The headers are inputs too: they carry the ABI structs
	// and the shared inline helpers, so a header-only edit still has to rebuild.
	// The device-free doctest harness lives under native/test/ and is a build-time
	// verification asset, not part of the shipped bridge: keep it out of the production
	// sources and headers so doctest's main and the test TUs never link into mc_dlss.dll.
	val nativeSources = nativeDirectory.asFileTree.matching { include("**/*.cpp"); exclude("test/**") }
	val nativeHeaders = nativeDirectory.asFileTree.matching { include("**/*.h"); exclude("test/**") }
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

tasks.named("build") {
	dependsOn(buildNativeDlss)
}

// M-5 rung: the device-free native logic proven in C++, in-process, on a machine with no
// device, no Streamline session, and no sl.interposer.lib. state.cpp/timing.cpp/common.cpp
// compile beside the doctest harness under native/test/ and link vulkan-1.lib only - the
// vk* entry points those units reference - so the whole rung exercises exactly the logic
// the device-bound live tests assert through their own frame fences, twice under one seam.
val nativeTestCompile = tasks.register<Exec>("nativeTestCompile") {
	group = "verification"
	description = "Compiles the device-free native doctest harness with MSVC."

	val nativeDirectory = layout.projectDirectory.dir("native")
	// The three device-free units under test. Every other native/ TU is device- or
	// session-bound and deliberately stays out of the harness.
	val internalSources = listOf("state.cpp", "timing.cpp", "common.cpp")
		.map { nativeDirectory.file("internal/$it").asFile }
	val nativeHeaders = nativeDirectory.asFileTree.matching { include("internal/*.h", "mc_dlss.h") }
	// Test headers (doctest.h) are inputs so a header-only edit rebuilds, but they are not
	// source arguments: the linker would otherwise try to consume doctest.h as an object.
	val testHeaders = nativeDirectory.asFileTree.matching { include("test/*.h") }
	val testSources = nativeDirectory.asFileTree.matching { include("test/*.cpp") }
	val outputDirectory = layout.buildDirectory.dir("native-test")
	inputs.files(internalSources, nativeHeaders, testSources, testHeaders)
	outputs.file(outputDirectory.map { it.file("native_tests.exe") })

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
		// The NGX and Streamline headers are needed for compilation (common.h includes
		// nvsdk_ngx.h, state.h includes sl_core_types.h); no NGX or Streamline library is
		// ever linked - if the link fails on a sl:: or NVSDK symbol, a wrong TU slipped in.
		val ngxHeader = ngxSdk.resolve("include/nvsdk_ngx.h")
		val streamlineHeader = streamlineSdk.resolve("include/sl.h")

		check(vsDevCmd.isFile) { "Visual Studio 2022 Build Tools missing: $vsDevCmd" }
		check(vulkanHeader.isFile) { "Vulkan SDK 1.4.357.0 header missing: $vulkanHeader (set VULKAN_SDK or install at C:/VulkanSDK/1.4.357.0)" }
		check(vulkanLibrary.isFile) { "Vulkan SDK 1.4.357.0 loader library missing: $vulkanLibrary" }
		check(ngxHeader.isFile) { "Pinned NVIDIA DLSS SDK 310.7.0 header (reference vocabulary only) missing: $ngxHeader" }
		check(streamlineHeader.isFile) { "Pinned Streamline 2.12.0 header missing: $streamlineHeader" }

		val outputDir = outputDirectory.get().asFile.apply { mkdirs() }
		val output = outputDir.resolve("native_tests.exe")
		// Object directory, not object file: with more than one source, /Fo must name a
		// directory and must end in a separator, or cl.exe writes every object over the same
		// name and links only the last one. The separator is a forward slash - which MSVC
		// accepts in paths - because a trailing backslash would escape the closing quote and
		// swallow the rest of the command line into one argument.
		val objectDir = outputDir.resolve("obj").apply { mkdirs() }
		val objectDirArgument = objectDir.absolutePath.replace('\\', '/') + "/"
		// Sorted so the command line - and therefore the up-to-date check - does not depend on
		// the order the file tree happens to walk in.
		val sourceArguments = (internalSources + testSources.files.sorted())
			.joinToString(" ") { "\"${it.absolutePath}\"" }
		commandLine(
			"cmd.exe", "/d", "/c",
			"call \"${vsDevCmd.absolutePath}\" -arch=x64 -host_arch=x64 && " +
				"cl.exe /nologo /std:c++17 /EHsc /O2 /DNOMINMAX /Fo\"${objectDirArgument}\" " +
				// native/ first: the internal units include each other as "internal/<unit>.h"
				// and the public header as "mc_dlss.h", both relative to it.
				"/I\"${nativeDirectory.asFile.absolutePath}\" " +
				"/I\"${vulkanSdk.resolve("Include").absolutePath}\" " +
				"/I\"${ngxSdk.resolve("include").absolutePath}\" " +
				"/I\"${streamlineSdk.resolve("include").absolutePath}\" " +
				sourceArguments + " " +
				"/link /OUT:\"${output.absolutePath}\" " +
				"\"${vulkanLibrary.absolutePath}\""
		)
	}
}

val nativeTest = tasks.register<Exec>("nativeTest") {
	group = "verification"
	description = "Runs the device-free native doctest harness."
	dependsOn(nativeTestCompile)
	// No outputs on purpose: the binary is cheap, and an up-to-date skip must never hide a
	// failed run - a doctest failure is a failing task, every time.
	commandLine(layout.buildDirectory.dir("native-test").get().asFile.resolve("native_tests.exe").absolutePath)
}

// The half of the root's `checkLayering` that has to live here: resolving this project's
// compileClasspath from a task the root project owns fails on this project's state lock.
// Nothing engine-shaped may appear on it - the SDK compiles without Minecraft, Fabric, or
// Blaze3D, which is what makes it usable by a mod that is not this one.
val checkEngineFreeClasspath = tasks.register("checkEngineFreeClasspath") {
	group = "verification"
	description = "Asserts no Minecraft/Fabric/Blaze3D coordinate reaches the SDK compile classpath."

	val engineCoordinate = Regex("(?i)minecraft|fabric|blaze3d|com\\.mojang")
	val coordinates = configurations.named("compileClasspath")
		.flatMap { it.incoming.artifacts.resolvedArtifacts }
		.map { artifacts -> artifacts.map { it.id.componentIdentifier.displayName } }
	inputs.property("coordinates", coordinates)

	doLast {
		val engine = coordinates.get().filter(engineCoordinate::containsMatchIn)
		check(engine.isEmpty()) {
			engine.joinToString("\n", prefix = "Engine coordinates on the SDK compile classpath:\n") { "  - $it" }
		}
	}
}

// The suite split (test vs nativeBridgeTest, one JVM per bridge-loading class because
// Streamline's runtime accepts one Vulkan device per process) is a convention; see buildSrc.
tasks.named("check") {
	dependsOn(nativeTest, tasks.named("nativeBridgeTest"), checkEngineFreeClasspath)
}

// The SDK owns its native assets: the bridge and the nine Streamline/NGX runtime dlls are
// staged under the SDK's own resource namespace, so they ride the nested SDK jar into the
// produced mod jar's META-INF/jars. The dev client's working directory is `run/`, which is
// why a repository-relative path cannot be used.
val streamlineRuntime = toolchainRoot("mc.dlss.streamline-sdk", "STREAMLINE_SDK", DEFAULT_STREAMLINE_SDK)
	.resolve("bin/x64")
val streamlineRuntimeFiles = listOf(
	"sl.interposer.dll", "sl.common.dll", "sl.dlss.dll", "sl.dlss_g.dll", "sl.reflex.dll",
	"sl.pcl.dll",
	"nvngx_dlss.dll", "nvngx_dlssg.dll", "NvLowLatencyVk.dll"
)

tasks.processResources {
	// The Fabric library-mod identity is ${version}-expanded here so the nested jar's
	// fabric.mod.json declares the real version (1.0.0) instead of leaving ${version} literal.
	val version = version
	inputs.property("version", version)
	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}

	from(buildNativeDlss) {
		into("assets/streamline-api/native")
	}
	from(streamlineRuntimeFiles.map(streamlineRuntime::resolve)) {
		into("assets/streamline-api/native/streamline")
	}
	// Windows resolves mc_dlss.dll dependencies beside the bridge before bootstrap can provide
	// the plugin search path. Keep a colocated generated copy; proprietary binaries remain external.
	from(streamlineRuntimeFiles.map(streamlineRuntime::resolve)) {
		into("assets/streamline-api/native")
	}
}
