import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	id("net.fabricmc.fabric-loom")
	id("org.jetbrains.kotlin.jvm")
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

// `:streamline` provisions the pinned SDKs. Machine-local configuration remains an override.
val provisionedDlssSdk = providers.provider {
	val version = providers.gradleProperty("dlss_sdk_version").get()
	val commit = providers.gradleProperty("dlss_sdk_commit").get()
	File(gradle.gradleUserHomeDir, "caches/mc-dlss/vendor-sdks/dlss-$version/DLSS-$commit")
}
val ngxSdkRoot = providers.gradleProperty("mc.dlss.ngx-sdk")
	.orElse(providers.environmentVariable("NGX_SDK"))
	.map(::file)
	.orElse(provisionedDlssSdk)

java {
	withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 25
}

kotlin {
	compilerOptions {
		jvmTarget = JvmTarget.JVM_25
	}
}

// A crashing JVM writes hs_err/replay dumps to its working directory, which for a Gradle worker
// is the project directory. The bridge can fault inside NVIDIA's own libraries, and
// `forkEvery = 1` turns one bad run into one dump per test class, so every forked JVM is pointed
// at build/jvm-crash instead of littering the repository.

// Only the classes that load the bridge need a process of their own - Streamline's runtime
// accepts one Vulkan device per process - and a fork costs a fresh JVM plus classpath loading.
// Everything else shares one worker in `test`. The tag mirrors the @NativeBridge annotation the
// test sources carry.
val nativeBridgeTag = "native-bridge"

tasks.test {
	useJUnitPlatform { excludeTags(nativeBridgeTag) }
}

repositories {
	// Loom supplies the Minecraft/Fabric repositories; this one carries the ordinary Maven
	// artifacts (JUnit, detekt-cli).
	mavenCentral()
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
	// Versions live in gradle.properties.
	minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
	implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
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

// The optional bridge-loading test path reproduces the StreamlineVulkanProvider redirect in a
// dedicated worker. Production uses manual proxy calls; pass
// -Pmc.dlss.vulkan-libname=<abs path to sl.interposer.dll> only when testing the interposer path.
val nativeBridgeTest = tasks.register<Test>("nativeBridgeTest") {
	group = "verification"
	description = "Runs the @NativeBridge test classes, one JVM per class."
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform { includeTags(nativeBridgeTag) }
	forkEvery = 1
	jvmArgs("--enable-native-access=ALL-UNNAMED")
	providers.gradleProperty("mc.dlss.vulkan-libname").orNull
		?.let { systemProperty("org.lwjgl.vulkan.libname", it) }
}

// The split is only real while something enforces it. `checkLayering` decides the structural
// boundaries between the two projects in one task, so `build` fails the moment one regresses
// rather than months later when a Blaze3D import has quietly grown back into the SDK.
// The FFM surface, by the names a downcall cannot avoid spelling.
val ffmSymbol = Regex("\\b(java\\.lang\\.foreign|MemorySegment|SymbolLookup|MemoryLayout|ValueLayout|FunctionDescriptor|Arena)\\b")
// NVIDIA's own vocabulary: the NGX names, and Streamline's `sl`-prefixed C entry points. The
// SDK's Java types are deliberately not matched - `SlVulkanFeatures` is how the mod is supposed
// to reach the native stack, and naming it is different from speaking NGX.
val nativeVocabulary =
	Regex("\\b(NVSDK\\w*|NGX\\w*|ngx[A-Z]\\w*|sl(Init|Shutdown|SetTag|SetConstants|Evaluate\\w*|Allocate\\w*|Free\\w*|Get\\w+|Is\\w+|Upgrade\\w*))\\b")
// Comments are stripped before the vocabulary match: prose may explain native terminology
// without creating a dependency.
val commentOrString = Regex("/\\*[\\s\\S]*?\\*/|//[^\\n]*")

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
			val source = file.readText()
			ffmSymbol.find(source)?.let {
				violations += ":mc-dlss reaches the native stack through the SDK, but ${file.path} spells FFM: ${it.value}"
			}
			nativeVocabulary.find(commentOrString.replace(source, ""))?.let {
				violations += ":mc-dlss speaks no NGX/Streamline vocabulary, but ${file.path} names: ${it.value}"
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

// Development-only dev-client wiring. Loom's run-config `property(...)` never reaches
// launch.cfg, so the DLSS startup properties are set on the run task's JVM directly. Every
// value is overridable, e.g. `./gradlew.bat runClient -Pmc.dlss.mode=performance`.
tasks.withType<JavaExec>().matching { it.name.startsWith("runClient") }.configureEach {
	dependsOn(":streamline:provisionDlssSdk")
	// Same restricted-method warning as the test task: System::load plus FFM downcalls.
	jvmArgs("--enable-native-access=ALL-UNNAMED")

	// DLSS only supports Minecraft's Vulkan backend. Force it for every dev-client launch so a
	// persisted OpenGL option cannot produce a misleading waiting-for-vulkan session.
	args("--graphicsBackend", "vulkan")

	// sdk-path is a compatibility input: the retired direct-NGX path searched it for its
	// feature DLL, and initialize now validates it and records only the Vulkan tuple.
	val sdkPath = ngxSdkRoot.map { it.resolve("lib/Windows_x86_64/rel").absolutePath }
	val dlssData = layout.buildDirectory.dir("dlss-data").get().asFile

	doFirst {
		check(args.windowed(2).any { it == listOf("--graphicsBackend", "vulkan") }) {
			"DLSS runClient requires --graphicsBackend vulkan"
		}
		dlssData.mkdirs()
	}

	systemProperty("mc.dlss.sdk-path", providers.gradleProperty("mc.dlss.sdk-path").orElse(sdkPath))
	systemProperty("mc.dlss.data-path", providers.gradleProperty("mc.dlss.data-path").getOrElse(dlssData.absolutePath))
	// Performance mode by default: the widest render/output gap, so a routing change is easiest
	// to see. Quality and balanced remain a -Pmc.dlss.mode away.
	systemProperty("mc.dlss.mode", providers.gradleProperty("mc.dlss.mode").getOrElse("performance"))
	for (name in listOf("mc.dlss.enabled", "mc.dlss.output-width", "mc.dlss.output-height")) {
		providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
	}
}

/** Static analysis over every Kotlin source in the repository, both projects at once. detekt.yml
is a delta over detekt's defaults, so each relaxation in it records a deliberate repo choice
and the findings that remain are defects rather than disagreements about shape.

The CLI rather than the Gradle plugin, because detekt 1.23 (the newest release) predates JDK
25 and dies parsing a "25.0.4" version string in whatever JVM it runs in. As a JavaExec it
runs on the workstation's JDK 21 while the rest of the build stays on 25. Revisit when detekt
2 ships: the plugin is the nicer wiring once it can run on the daemon's JDK. **/
val detektCli = configurations.create("detektCli")

dependencies {
	detektCli("io.gitlab.arturbosch.detekt:detekt-cli:1.23.8")
}

val detekt = tasks.register<JavaExec>("detekt") {
	group = "verification"
	description = "Runs detekt over the Kotlin sources of both projects."

	val sources = listOf(
		"src/main/kotlin", "src/test/kotlin",
		"streamline/src/test/kotlin", "streamline/src/testFixtures/kotlin",
	).map(layout.projectDirectory::dir)
	val report = layout.buildDirectory.file("reports/detekt/detekt.html")
	inputs.files(sources.map { fileTree(it) { include("**/*.kt") } })
	inputs.file("detekt.yml")
	outputs.file(report)

	classpath = detektCli
	mainClass = "io.gitlab.arturbosch.detekt.cli.Main"
	javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
	args(
		"--config", file("detekt.yml").absolutePath,
		"--build-upon-default-config",
		"--input", sources.joinToString(",") { it.asFile.absolutePath },
		"--report", "html:${report.get().asFile.absolutePath}",
	)
}

tasks.check { dependsOn(detekt) }

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
