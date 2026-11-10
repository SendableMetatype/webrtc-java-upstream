/*
 * Runs the tests of a project inside its module, so that they reach its
 * package private classes and meet the module's boundaries as an application
 * on the module path does: the compiled classes of the project and the jars of
 * the modules it depends on are on the module path, the resources and the test
 * classes are patched into the module, and JUnit and the native libraries are
 * on the class path.
 *
 * The module is the directory of classes, not the jar: some tests walk the
 * place their classes were loaded from.
 */

// webrtc.java for the core project, webrtc.java.media for webrtc-media.
val moduleName = "webrtc.java" + name.removePrefix("webrtc").replace('-', '.')

val sourceSets = the<SourceSetContainer>()
val mainOutput = sourceSets["main"].output
val testOutput = sourceSets["test"].output

tasks.named<Test>("test") {
	val modulePath = mainOutput.classesDirs + configurations["runtimeClasspath"]
	// Gradle keeps the resources apart from the classes, so they are patched
	// in beside the test classes and the test resources.
	val patches = files(mainOutput.resourcesDir) + testOutput

	// The class path holds what is not on the module path.
	classpath = configurations["testRuntimeClasspath"] - configurations["runtimeClasspath"]

	// The module and the patches reach the tests through the arguments below,
	// which Gradle does not look into: without the inputs, a change to the
	// library alone would leave the tests up to date.
	inputs.files(mainOutput).withPropertyName("module")
	inputs.files(configurations["runtimeClasspath"]).withPropertyName("moduleDependencies")
	inputs.files(testOutput).withPropertyName("testOutput")

	jvmArgumentProviders.add(CommandLineArgumentProvider {
		val patch = patches.filter { it.exists() }.asPath
		// JUnit creates the test classes reflectively, so every package that
		// holds one is opened, internal ones included. Taken from the compiled
		// tests, so that a new test package needs no entry here.
		val opens = testOutput.classesDirs.filter { it.isDirectory }.flatMap { dir ->
			dir.walk().filter { it.extension == "class" }
				.map { it.parentFile.relativeTo(dir).invariantSeparatorsPath.replace('/', '.') }.toList()
		}.distinct()

		listOf(
			"--module-path", modulePath.asPath,
			"--add-modules", moduleName,
			"--patch-module", "$moduleName=$patch",
			"--add-reads", "$moduleName=ALL-UNNAMED",
			// For the tests that start a JVM of their own: neither path names
			// the patched resources and test classes.
			"-Dwebrtc.test.classes=$patch",
		) + opens.flatMap { listOf("--add-opens", "$moduleName/$it=ALL-UNNAMED") }
	})
}
