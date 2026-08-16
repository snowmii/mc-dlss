plugins {
	`java-library`
}

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
}

val buildNativeDlss = tasks.register<Exec>("buildNativeDlss") {
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

tasks.named("build") {
	dependsOn(buildNativeDlss)
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 25
}