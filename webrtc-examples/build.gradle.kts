plugins {
	java
	alias(libs.plugins.graalvm.native)
}

val javafxClassifier = rootProject.extra["javafxClassifier"] as String

// The media examples need the natives of webrtc-java-media at run time. They
// are not a dependency, as for any application of that module, so the run
// task adds them, and the native image stays without them.
val mediaNatives = configurations.create("mediaNatives") {
	isCanBeConsumed = false
}

dependencies {
	implementation(project(":webrtc"))
	implementation(project(":webrtc-media"))
	implementation(project(":webrtc-swing"))
	implementation(project(":webrtc-javafx"))
	// The same OpenJFX line webrtc-java-javafx is compiled against.
	implementation(variantOf(libs.javafx.base) { classifier(javafxClassifier) })
	implementation(variantOf(libs.javafx.graphics) { classifier(javafxClassifier) })
	implementation(variantOf(libs.javafx.controls) { classifier(javafxClassifier) })
	implementation(libs.jetty.server)
	implementation(libs.jetty.websocket.server)
	implementation(libs.jetty.util)
	implementation(libs.jackson.databind)
	implementation(libs.slf4j.jdk14)

	runtimeOnly(project(path = ":webrtc-jni", configuration = "natives"))
	mediaNatives(project(path = ":webrtc-media", configuration = "natives"))
}

tasks.withType<JavaCompile>().configureEach {
	// The libraries target Java 11, but the examples need 17.
	options.release = 17
}

tasks.processResources {
	// The examples load their resources below "resources/".
	eachFile {
		relativePath = RelativePath(true, "resources", *relativePath.segments)
	}
	includeEmptyDirs = false
}

tasks.register<JavaExec>("run") {
	description = "Runs an example: gradlew :webrtc-examples:run -PmainClass=<class name> [--args=\"<arguments>\"]"
	group = "application"
	classpath = sourceSets.main.get().runtimeClasspath + mediaNatives
	mainClass = providers.gradleProperty("mainClass")
	// Several examples run until Enter is pressed.
	standardInput = System.`in`
}

// Compiles NativeImageExample into a GraalVM native image,
// build/native/nativeCompile/webrtc-native-image-example, which carries the
// native library inside itself. Needs GraalVM 24 or later as JAVA_HOME (or
// GRAALVM_HOME): gradlew :webrtc-examples:nativeCompile
graalvmNative {
	// The examples have no tests to compile into an image.
	testSupport = false

	// webrtc-java brings its own metadata; nothing else this example runs
	// needs any.
	metadataRepository {
		enabled = false
	}

	binaries {
		named("main") {
			imageName = "webrtc-native-image-example"
			mainClass = "dev.onvoid.webrtc.examples.NativeImageExample"

			// System.load is a restricted method from JDK 24 on, and the image
			// would warn about it on every start.
			buildArgs.add("--enable-native-access=ALL-UNNAMED")
			// A class, member or resource the metadata lacks fails at once,
			// instead of only where a lookup happens to find nothing, so that
			// the example checks the metadata of webrtc-java.
			buildArgs.add("--exact-reachability-metadata")
		}
	}
}
