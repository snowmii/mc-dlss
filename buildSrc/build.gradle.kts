plugins {
	`kotlin-dsl`
}

repositories {
	mavenCentral()
	gradlePluginPortal()
}

dependencies {
	// The convention plugin configures the Kotlin JVM extension of whichever project applies it,
	// so the Kotlin Gradle Plugin has to be on this build's compile classpath. Same version both
	// projects declare, so one toolchain serves all three.
	implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
}
