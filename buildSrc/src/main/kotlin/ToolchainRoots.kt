import org.gradle.api.Project
import java.io.File

/**
 * Resolves a workstation-local toolchain root: Gradle property first, then environment variable,
 * then the path the bridge was developed against. A second machine points these somewhere else
 * rather than patching a build script.
 */
fun Project.toolchainRoot(property: String, environment: String, default: String): File =
	providers.gradleProperty(property)
		.orElse(providers.environmentVariable(environment))
		.orElse(default)
		.map(::file)
		.get()
