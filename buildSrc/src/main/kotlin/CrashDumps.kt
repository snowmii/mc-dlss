import org.gradle.api.Task
import org.gradle.process.JavaForkOptions

/**
 * A crashing JVM writes hs_err/replay dumps to its working directory, which for a Gradle test
 * worker is the project directory. The DLSS native bridge can fault inside NVIDIA's own
 * libraries, and `forkEvery = 1` turns one bad run into one dump per test class, so every forked
 * JVM is pointed at `build/jvm-crash` instead of littering the repository.
 */
fun <T> T.redirectJvmCrashDumps() where T : Task, T : JavaForkOptions {
	val crashDirectory = project.layout.buildDirectory.dir("jvm-crash").get().asFile
	// %p expands to the pid, keeping concurrent workers from overwriting each other.
	jvmArgs(
		"-XX:ErrorFile=${crashDirectory.resolve("hs_err_pid%p.log")}",
		"-XX:ReplayDataFile=${crashDirectory.resolve("replay_pid%p.log")}",
	)
	// The JVM silently falls back to the working directory if the target is unwritable.
	doFirst { crashDirectory.mkdirs() }
}
