/*
 * For a project whose nativeJar task packages a native library: offers the jar
 * of the platform to the tests and examples, to other projects in the
 * "natives" configuration and to the project itself as "platformNatives".
 *
 * With natives.dir the jar comes from that directory, where it has the name
 * the nativeJar task gives it, and the native build is off.
 */

val nativesDir = rootProject.extra["nativesDir"] as File?
val nativeJar = tasks.named<Jar>("nativeJar")

val natives = configurations.create("natives") {
	isCanBeConsumed = true
	isCanBeResolved = false
}

if (nativesDir == null) {
	artifacts.add(natives.name, nativeJar)

	extra["platformNatives"] = files(nativeJar)
}
else {
	val prebuiltJar = nativesDir.resolve(nativeJar.get().archiveFileName.get())

	// Runs before anything uses the jar, so that a missing one is named here
	// instead of failing every test that loads the library.
	val checkPrebuiltNatives = tasks.register("checkPrebuiltNatives") {
		description = "Checks that natives.dir holds the native library jar of the platform."

		doLast {
			if (!prebuiltJar.isFile) {
				throw GradleException("natives.dir has no ${prebuiltJar.name}: $nativesDir")
			}
		}
	}

	artifacts.add(natives.name, prebuiltJar) {
		builtBy(checkPrebuiltNatives)
	}

	extra["platformNatives"] = files(prebuiltJar).builtBy(checkPrebuiltNatives)

	// Prebuilt native library jars replace the native build entirely.
	listOf("cmakeGenerate", "cmakeBuild", "nativeJar").forEach { task ->
		tasks.named(task) {
			onlyIf("no natives.dir is given") { false }
		}
	}
}
