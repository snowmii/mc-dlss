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

tasks.test { useJUnitPlatform() }

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

tasks.check { dependsOn(":streamline:check") }

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

	systemProperty("mc.dlss.sdk-path", providers.gradleProperty("mc.dlss.sdk-path").orElse(sdkPath).get())
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
