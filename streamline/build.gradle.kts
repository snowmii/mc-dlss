import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.OutputStream
import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest

plugins {
	`java-library`
	`java-test-fixtures`
	id("org.jetbrains.kotlin.jvm")
}

version = providers.gradleProperty("mod_version").get()

tasks.withType<JavaCompile>().configureEach {
	options.release = 25
}

kotlin {
	compilerOptions {
		jvmTarget = JvmTarget.JVM_25
	}
}

repositories {
	mavenCentral()
}

val junitVersion = providers.gradleProperty("junit_version").get()
val junitPlatformVersion = providers.gradleProperty("junit_platform_version").get()
val jomlVersion = providers.gradleProperty("joml_version").get()
val lwjglVersion = providers.gradleProperty("lwjgl_version").get()
val fastutilVersion = providers.gradleProperty("fastutil_version").get()

dependencies {
	implementation("org.joml:joml:$jomlVersion")
	implementation("org.lwjgl:lwjgl-vulkan:$lwjglVersion")

	testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitPlatformVersion")
	testRuntimeOnly("org.lwjgl:lwjgl:$lwjglVersion:natives-windows")
	testFixturesImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
	testFixturesImplementation("it.unimi.dsi:fastutil:$fastutilVersion")
	testFixturesImplementation("org.lwjgl:lwjgl-vulkan:$lwjglVersion")
}

val streamlineSdkVersion = providers.gradleProperty("streamline_sdk_version").get()
val streamlineDir = File(gradle.gradleUserHomeDir, "caches").resolve("streamline/$streamlineSdkVersion")
val streamlineArchive = streamlineDir.resolve("sdk.zip")


fun open(url: String) = URI(url).toURL().openConnection().apply {
	setRequestProperty("User-Agent", "mc-dlss")
}

fun get(url: String, output: File) {
	output.parentFile.mkdirs()
	val tmp = output.resolveSibling("${output.name}.part")
	if (tmp.exists()) check(tmp.delete()) { "Failed to clear $tmp" }
	val connection = open(url)
	val expectedLength = connection.contentLengthLong
	connection.getInputStream().use { input -> tmp.outputStream().use(input::copyTo) }
	val actualLength = tmp.length()
	if (expectedLength >= 0 && actualLength != expectedLength) {
		tmp.delete()
		error("Incomplete download: $url ($actualLength of $expectedLength bytes)")
	}
	if (output.exists()) check(output.delete()) { "Failed to replace $output" }
	check(tmp.renameTo(output)) { "Failed to publish $output" }
}

fun sha256(file: File): String {
	val digest = MessageDigest.getInstance("SHA-256")
	file.inputStream().use { DigestInputStream(it, digest).use { stream -> stream.copyTo(OutputStream.nullOutputStream()) } }
	return digest.digest().joinToString("") { "%02x".format(it) }
}

@Suppress("UNCHECKED_CAST")
fun githubAsset(repo: String, tag: String, name: String, output: File) {
	val release = open("https://api.github.com/repos/$repo/releases/tags/$tag").getInputStream().use { it.reader().readText() }
	val assets = (JsonSlurper().parseText(release) as Map<*, *>)["assets"] as List<Map<*, *>>
	val asset = assets.first { it["name"] == name }
	val expectedSize = (asset["size"] as Number).toLong()
	val expected = asset["digest"]?.toString()?.removePrefix("sha256:")
	check(expected != null) { "GitHub release $tag is missing a SHA-256 digest for $name" }
	if (!output.exists() || output.length() != expectedSize || sha256(output) != expected) {
		get(asset["browser_download_url"].toString(), output)
	}
	val actual = sha256(output)
	check(output.length() == expectedSize && actual == expected) {
		"SHA-256 mismatch: $name (expected $expected / $expectedSize bytes, got $actual / ${output.length()} bytes)"
	}
}

val downloadStreamlineSdk = tasks.register("downloadStreamlineSdk") {
	outputs.file(streamlineArchive)
	doLast {
		githubAsset(
			"NVIDIA-RTX/Streamline",
			"v$streamlineSdkVersion",
			"streamline-sdk-v$streamlineSdkVersion.zip",
			streamlineArchive,
		)
	}
}
val provisionStreamlineSdk = tasks.register<Sync>("provisionStreamlineSdk") {
	dependsOn(downloadStreamlineSdk)
	from({ zipTree(streamlineArchive) })
	into(streamlineDir)
}
val provisionedStreamlineSdk = providers.provider { streamlineDir }

// I'm not even gonna pretend that I understand wtf is going on down below
fun toolchainRoot(property: String, environment: String, fallback: Provider<File>? = null) =
	providers.gradleProperty(property)
		.orElse(providers.environmentVariable(environment))
		.map(::file)
		.let { configured -> fallback?.let(configured::orElse) ?: configured }
		.orElse(providers.provider {
			throw GradleException(
				"Missing toolchain path: set -P$property=<path> or $environment; see README.md"
			)
		})

val buildNativeDlss = tasks.register<Exec>("buildNativeDlss") {
	group = "build"
	dependsOn(provisionStreamlineSdk)

	val nativeDirectory = layout.projectDirectory.dir("native")
	val nativeSources = nativeDirectory.asFileTree.matching { include("**/*.cpp"); exclude("test/**") }
	val nativeHeaders = nativeDirectory.asFileTree.matching { include("**/*.h"); exclude("test/**") }
	val motionShader = nativeDirectory.file("mc_dlss_motion.comp")
	val velocityFillShader = nativeDirectory.file("mc_dlss_velocity_fill.comp")
	val outputDirectory = layout.buildDirectory.dir("native")
	inputs.files(nativeSources, nativeHeaders, motionShader, velocityFillShader)
	outputs.file(outputDirectory.map { it.file("mc_dlss.dll") })

	doFirst {
		val vsDevCmd = toolchainRoot("mc.dlss.vs-dev-cmd", "VSDEVCMD").get()
		val vulkanSdk = toolchainRoot("mc.dlss.vulkan-sdk", "VULKAN_SDK").get()
		val streamlineSdk = toolchainRoot(
			"mc.dlss.streamline-sdk", "STREAMLINE_SDK", provisionedStreamlineSdk
		).get()
		val ngxSdk = streamlineSdk.resolve("external/ngx-sdk")
		val vulkanHeader = vulkanSdk.resolve("Include/vulkan/vulkan.h")
		val vulkanLibrary = vulkanSdk.resolve("Lib/vulkan-1.lib")
		val ngxHeader = ngxSdk.resolve("include/nvsdk_ngx.h")
		val streamlineHeader = streamlineSdk.resolve("include/sl.h")
		val streamlineLibrary = streamlineSdk.resolve("lib/x64/sl.interposer.lib")
		val glslc = vulkanSdk.resolve("Bin/glslc.exe")

		check(vsDevCmd.isFile) { "Visual Studio 2022 Build Tools missing: $vsDevCmd" }
		check(glslc.isFile) { "Vulkan SDK shader compiler missing: $glslc" }
		check(vulkanHeader.isFile) { "Vulkan SDK header missing: $vulkanHeader" }
		check(vulkanLibrary.isFile) { "Vulkan SDK loader library missing: $vulkanLibrary" }
		check(ngxHeader.isFile) { "NVIDIA NGX header missing: $ngxHeader" }
		check(streamlineHeader.isFile) { "Pinned Streamline $streamlineSdkVersion header missing: $streamlineHeader" }
		check(streamlineLibrary.isFile) { "Pinned Streamline $streamlineSdkVersion interposer library missing: $streamlineLibrary" }

		val outputDir = outputDirectory.get().asFile.apply { mkdirs() }
		val output = outputDir.resolve("mc_dlss.dll")
		val motionSpirV = outputDir.resolve("mc_dlss_motion.spv.h")
		val velocityFillSpirV = outputDir.resolve("mc_dlss_velocity_fill.spv.h")
		val objectDir = outputDir.resolve("obj").apply { mkdirs() }
		val objectDirArgument = objectDir.absolutePath.replace('\\', '/') + "/"
		val sourceArguments = nativeSources.files.sorted().joinToString(" ") { "\"${it.absolutePath}\"" }
		commandLine(
			"cmd.exe", "/d", "/c",
			"call \"${vsDevCmd.absolutePath}\" -arch=x64 -host_arch=x64 && " +
				"\"${glslc.absolutePath}\" -O --target-env=vulkan1.2 -mfmt=c " +
				"-o \"${motionSpirV.absolutePath}\" \"${motionShader.asFile.absolutePath}\" && " +
				"\"${glslc.absolutePath}\" -O --target-env=vulkan1.2 -mfmt=c " +
				"-o \"${velocityFillSpirV.absolutePath}\" \"${velocityFillShader.asFile.absolutePath}\" && " +
				"cl.exe /nologo /std:c++17 /EHsc /LD /O2 /DNOMINMAX /Fo\"${objectDirArgument}\" " +
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

val nativeTestCompile = tasks.register<Exec>("nativeTestCompile") {
	group = "verification"
	dependsOn(provisionStreamlineSdk)

	val nativeDirectory = layout.projectDirectory.dir("native")
	val internalSources = listOf("state.cpp", "timing.cpp", "common.cpp")
		.map { nativeDirectory.file("internal/$it").asFile }
	val nativeHeaders = nativeDirectory.asFileTree.matching { include("internal/*.h", "mc_dlss.h") }
	val testHeaders = nativeDirectory.asFileTree.matching { include("test/*.h") }
	val testSources = nativeDirectory.asFileTree.matching { include("test/*.cpp") }
	val outputDirectory = layout.buildDirectory.dir("native-test")
	inputs.files(internalSources, nativeHeaders, testSources, testHeaders)
	outputs.file(outputDirectory.map { it.file("native_tests.exe") })

	doFirst {
		val vsDevCmd = toolchainRoot("mc.dlss.vs-dev-cmd", "VSDEVCMD").get()
		val vulkanSdk = toolchainRoot("mc.dlss.vulkan-sdk", "VULKAN_SDK").get()
		val streamlineSdk = toolchainRoot(
			"mc.dlss.streamline-sdk", "STREAMLINE_SDK", provisionedStreamlineSdk
		).get()
		val ngxSdk = streamlineSdk.resolve("external/ngx-sdk")
		val vulkanHeader = vulkanSdk.resolve("Include/vulkan/vulkan.h")
		val vulkanLibrary = vulkanSdk.resolve("Lib/vulkan-1.lib")
		val ngxHeader = ngxSdk.resolve("include/nvsdk_ngx.h")
		val streamlineHeader = streamlineSdk.resolve("include/sl.h")

		check(vsDevCmd.isFile) { "Visual Studio 2022 Build Tools missing: $vsDevCmd" }
		check(vulkanHeader.isFile) { "Vulkan SDK header missing: $vulkanHeader" }
		check(vulkanLibrary.isFile) { "Vulkan SDK loader library missing: $vulkanLibrary" }
		check(ngxHeader.isFile) { "NVIDIA NGX header missing: $ngxHeader" }
		check(streamlineHeader.isFile) { "Pinned Streamline $streamlineSdkVersion header missing: $streamlineHeader" }

		val outputDir = outputDirectory.get().asFile.apply { mkdirs() }
		val output = outputDir.resolve("native_tests.exe")
		val objectDir = outputDir.resolve("obj").apply { mkdirs() }
		val objectDirArgument = objectDir.absolutePath.replace('\\', '/') + "/"
		val sourceArguments = (internalSources + testSources.files.sorted())
			.joinToString(" ") { "\"${it.absolutePath}\"" }
		commandLine(
			"cmd.exe", "/d", "/c",
			"call \"${vsDevCmd.absolutePath}\" -arch=x64 -host_arch=x64 && " +
				"cl.exe /nologo /std:c++17 /EHsc /O2 /DNOMINMAX /Fo\"${objectDirArgument}\" " +
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
	dependsOn(nativeTestCompile)
	commandLine(layout.buildDirectory.dir("native-test").get().asFile.resolve("native_tests.exe").absolutePath)
}

val nativeBridgeTag = "native-bridge"

tasks.test {
	useJUnitPlatform { excludeTags(nativeBridgeTag) }
}

val nativeBridgeTest = tasks.register<Test>("nativeBridgeTest") {
	group = "verification"
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform { includeTags(nativeBridgeTag) }
	forkEvery = 1
	jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named("check") {
	dependsOn(nativeTest, nativeBridgeTest)
}

val streamlineRuntime = toolchainRoot(
	"mc.dlss.streamline-sdk", "STREAMLINE_SDK", provisionedStreamlineSdk
).map { it.resolve("bin/x64") }
val streamlineRuntimeFiles = listOf(
	"sl.interposer.dll", "sl.common.dll", "sl.dlss.dll", "sl.dlss_g.dll", "sl.reflex.dll",
	"sl.pcl.dll",
	"nvngx_dlss.dll", "nvngx_dlssg.dll", "NvLowLatencyVk.dll"
)

tasks.processResources {
	dependsOn(provisionStreamlineSdk)
	val version = version
	inputs.property("version", version)
	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}

	from(buildNativeDlss) {
		into("assets/streamline-api/native")
	}
	from(streamlineRuntimeFiles.map { name -> streamlineRuntime.map { it.resolve(name) } }) {
		into("assets/streamline-api/native")
	}
}
