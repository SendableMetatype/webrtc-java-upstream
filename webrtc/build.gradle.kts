plugins {
	`java-library`
	`maven-publish`
	signing
	alias(libs.plugins.nmcp)
}

apply(from = rootProject.file("gradle/module-tests.gradle.kts"))

evaluationDependsOn(":webrtc-jni")

val platformClassifier = rootProject.extra["platformClassifier"] as String
val nativesDir = rootProject.extra["nativesDir"] as File?
@Suppress("UNCHECKED_CAST")
val nativeClassifiers = rootProject.extra["nativeClassifiers"] as List<String>

dependencies {
	testRuntimeOnly(project(path = ":webrtc-jni", configuration = "natives"))
}

tasks.test {
	// The module does not read java.desktop, but a desktop capture test opens
	// an AWT window of its own to capture.
	jvmArgs("--add-modules", "java.desktop", "--add-reads", "webrtc.java=java.desktop")
}

publishing {
	publications {
		create<MavenPublication>("maven") {
			from(components["java"])

			if (nativesDir != null) {
				// A release: the native library jars of every platform, collected
				// from the per-platform builds. Maven Central publishes a version as
				// one atomic bundle, hence all classifiers go into one deployment.
				nativeClassifiers.forEach { nativeClassifier ->
					artifact(nativesDir.resolve("webrtc-java-${project.version}-$nativeClassifier.jar")) {
						classifier = nativeClassifier
						builtBy(rootProject.tasks.named("checkNatives"))
					}
				}
			}
			else {
				// The native library jar webrtc-jni has just built for this platform.
				artifact(project(":webrtc-jni").tasks.named<Jar>("nativeJar")) {
					classifier = platformClassifier
				}
			}

			pom {
				withXml {
					// Resolved by the consumer's Maven through the profiles of the
					// parent pom, see the root build script.
					val dependency = asNode().appendNode("dependencies").appendNode("dependency")
					dependency.appendNode("groupId", project.group)
					dependency.appendNode("artifactId", "webrtc-java")
					dependency.appendNode("version", project.version)
					dependency.appendNode("classifier", "\${platform.classifier}")
				}
			}
		}
	}
}
