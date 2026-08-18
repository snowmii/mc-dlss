import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	id("net.fabricmc.fabric-loom")
	id("org.jetbrains.kotlin.jvm")
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

val junitVersion = providers.gradleProperty("junit_version").get()
val junitPlatformVersion = providers.gradleProperty("junit_platform_version").get()
val detektVersion = providers.gradleProperty("detekt_version").get()

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

repositories {
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



fun DependencyHandlerScope.runtimeMod(property: String, coordinate: (String) -> String) {
	providers.gradleProperty(property).orNull?.takeIf(String::isNotBlank)?.let {
		"localRuntime"(coordinate(it)) { isTransitive = false }
	}
}

val detektCli = configurations.create("detektCli")

dependencies {
	minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
	implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
	implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
	implementation("net.fabricmc:fabric-language-kotlin:${providers.gradleProperty("fabric_kotlin_version").get()}")
	implementation(project(":streamline"))
	testImplementation(testFixtures(project(":streamline")))
	include(project(":streamline"))
	testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitPlatformVersion")

	runtimeMod("modmenu_version") { "maven.modrinth:modmenu:$it" }
	providers.gradleProperty("sodium_version").orNull?.let {
		compileOnly("maven.modrinth:sodium:$it") { isTransitive = false }
	}
	//runtimeMod("sodium_version") { "maven.modrinth:sodium:$it" }
	runtimeMod("devauth_version") { "me.djtheredstoner:DevAuth-fabric:$it" }

	detektCli("io.gitlab.arturbosch.detekt:detekt-cli:$detektVersion")
}

tasks.test {
	useJUnitPlatform()
}

tasks.check {
	dependsOn(":streamline:check", "detekt")
}

tasks.processResources {
	val version = version
	inputs.property("version", version)

	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}
}

tasks.withType<JavaExec>().matching { it.name.startsWith("runClient") }.configureEach {
	dependsOn(":streamline:provisionStreamlineSdk")
	jvmArgs("--enable-native-access=ALL-UNNAMED")
	args("--graphicsBackend", "vulkan")

	val dlssData = layout.buildDirectory.dir("dlss-data").get().asFile
	val sdkPath = providers.gradleProperty("mc.dlss.sdk-path").orElse(dlssData.absolutePath)

	doFirst {
		check(args.windowed(2).any { it == listOf("--graphicsBackend", "vulkan") }) {
			"DLSS runClient requires --graphicsBackend vulkan"
		}
		dlssData.mkdirs()
	}

	systemProperty("mc.dlss.sdk-path", sdkPath.get())
	systemProperty("mc.dlss.data-path", providers.gradleProperty("mc.dlss.data-path").getOrElse(dlssData.absolutePath))
	for (name in listOf(
		"mc.dlss.enabled",
		"mc.dlss.mode",
		"mc.dlss.preset",
		"mc.dlss.output-width",
		"mc.dlss.output-height",
	)) {
		providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
	}
}

val detekt = tasks.register<JavaExec>("detekt") {
	description = "detekt"
	group = "verification"

	val sources = listOf(
		"src/main/kotlin",
		"src/test/kotlin",
		"streamline/src/test/kotlin",
		"streamline/src/testFixtures/kotlin",
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

tasks.jar {
	val projectName = project.name
	inputs.property("projectName", projectName)

	from("LICENSE") {
		rename { "${it}_$projectName" }
	}
	from("THIRD-PARTY-NOTICES.md") {
		rename { "${it}_$projectName" }
	}
}
