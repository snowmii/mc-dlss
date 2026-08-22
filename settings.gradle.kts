pluginManagement {
	repositories {
		maven {
			name = "Fabric"
			url = uri("https://maven.fabricmc.net/")
		}
		mavenCentral()
		gradlePluginPortal()
	}

	plugins {
		id("net.fabricmc.fabric-loom") version providers.gradleProperty("loom_version")
		id("org.jetbrains.kotlin.jvm") version providers.gradleProperty("kotlin_version")
	}
}

rootProject.name = "mc-dlss"

include("streamline")
includeBuild("../vk-lib") {
	dependencySubstitution {
		substitute(module("me.snowmii:vk-lib")).using(project(":"))
		substitute(module("me.snowmii:vk-lib-api")).using(project(":api"))
	}
}
