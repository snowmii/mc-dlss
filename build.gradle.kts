import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	id("net.fabricmc.fabric-loom")
	`maven-publish`
	id("org.jetbrains.kotlin.jvm") version "2.4.10"
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

repositories {
	// Add repositories to retrieve artifacts from in here.
	// You should only use this when depending on other mods because
	// Loom adds the essential maven repositories to download Minecraft and libraries from automatically.
	// See https://docs.gradle.org/current/userguide/declaring_repositories.html
	// for more information about repositories.
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
}

tasks.test {
	useJUnitPlatform()
}

val buildNativeDlss by tasks.registering(Exec::class) {
	group = "build"
	description = "Builds the workstation-local DLSS native bridge with MSVC."

	val nativeSource = layout.projectDirectory.file("native/mc_dlss.cpp")
	val nativeHeader = layout.projectDirectory.file("native/mc_dlss.h")
	val outputDirectory = layout.buildDirectory.dir("native")
	inputs.files(nativeSource, nativeHeader)
	outputs.file(outputDirectory.map { it.file("mc_dlss.dll") })

	doFirst {
		val vsDevCmd = file("C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/Common7/Tools/VsDevCmd.bat")
		val vulkanSdk = providers.environmentVariable("VULKAN_SDK")
			.orElse("C:/VulkanSDK/1.4.357.0")
			.get()
			.let(::file)
		val ngxSdk = file("C:/Users/miuki/Development/NVIDIA/mc-dlss/dlss-sdk-v310.7.0/DLSS-310.7.0")
		val vulkanHeader = vulkanSdk.resolve("Include/vulkan/vulkan.h")
		val vulkanLibrary = vulkanSdk.resolve("Lib/vulkan-1.lib")
		val ngxHeader = ngxSdk.resolve("include/nvsdk_ngx.h")
		val ngxLibrary = ngxSdk.resolve("lib/Windows_x86_64/x64/nvsdk_ngx_s.lib")

		check(vsDevCmd.isFile) { "Visual Studio 2022 Build Tools missing: $vsDevCmd" }
		check(vulkanHeader.isFile) { "Vulkan SDK 1.4.357.0 header missing: $vulkanHeader (set VULKAN_SDK or install at C:/VulkanSDK/1.4.357.0)" }
		check(vulkanLibrary.isFile) { "Vulkan SDK 1.4.357.0 loader library missing: $vulkanLibrary" }
		check(ngxHeader.isFile) { "Pinned NVIDIA DLSS SDK 310.7.0 header missing: $ngxHeader" }
		check(ngxLibrary.isFile) { "Pinned NVIDIA DLSS SDK 310.7.0 library missing: $ngxLibrary" }

		val outputDir = outputDirectory.get().asFile.apply { mkdirs() }
		val output = outputDir.resolve("mc_dlss.dll")
		commandLine(
			"cmd.exe", "/d", "/c",
			"call \"${vsDevCmd.absolutePath}\" -arch=x64 -host_arch=x64 && " +
				"cl.exe /nologo /std:c++17 /EHsc /LD /O2 /DNOMINMAX /Fo\"${outputDir.resolve("mc_dlss.obj").absolutePath}\" " +
				"/I\"${vulkanSdk.resolve("Include").absolutePath}\" " +
				"/I\"${ngxSdk.resolve("include").absolutePath}\" " +
				"\"${nativeSource.asFile.absolutePath}\" " +
				"/link /OUT:\"${output.absolutePath}\" " +
				"/IMPLIB:\"${outputDir.resolve("mc_dlss.lib").absolutePath}\" " +
				"\"${vulkanLibrary.absolutePath}\" \"${ngxLibrary.absolutePath}\" Advapi32.lib User32.lib"
		)
	}
}

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
}

// Development-only dev-client wiring. Loom's run-config `property(...)` never reaches
// launch.cfg, so the DLSS startup properties are set on the run task's JVM directly. Every
// value is overridable, e.g. `./gradlew.bat runClient -Pmc.dlss.mode=performance`.
tasks.withType<JavaExec>().matching { it.name.startsWith("runClient") }.configureEach {
	// Directory holding nvngx_dlss.dll; NGX uses it as the feature search path.
	val ngxRuntime = file("C:/Users/miuki/Development/NVIDIA/mc-dlss/dlss-sdk-v310.7.0/DLSS-310.7.0/lib/Windows_x86_64/rel")
	val dlssData = layout.buildDirectory.dir("dlss-data").get().asFile

	doFirst {
		dlssData.mkdirs()
	}

	systemProperty("mc.dlss.sdk-path", providers.gradleProperty("mc.dlss.sdk-path").getOrElse(ngxRuntime.absolutePath))
	systemProperty("mc.dlss.data-path", providers.gradleProperty("mc.dlss.data-path").getOrElse(dlssData.absolutePath))
	for (name in listOf("mc.dlss.enabled", "mc.dlss.mode", "mc.dlss.output-width", "mc.dlss.output-height")) {
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

// configure the maven publication
publishing {
	publications {
		register<MavenPublication>("mavenJava") {
			from(components["java"])
		}
	}

	// See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
	repositories {
		// Add repositories to publish to here.
		// Notice: This block does NOT have the same function as the block in the top level.
		// The repositories here will be used for publishing your artifact, not for
		// retrieving dependencies.
	}
}
