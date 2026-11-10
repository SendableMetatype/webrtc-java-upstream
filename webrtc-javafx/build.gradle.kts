plugins {
	`java-library`
	`maven-publish`
	signing
	alias(libs.plugins.nmcp)
}

val javafxClassifier = rootProject.extra["javafxClassifier"] as String

description = "JavaFX extension for webrtc-java. Shows the video of the client API in JavaFX scenes."

dependencies {
	api(project(":webrtc"))

	// Optional in the published pom, see below: an application that uses this
	// module has JavaFX already, in the version and for the platforms it chose.
	compileOnly(variantOf(libs.javafx.base) { classifier(javafxClassifier) })
	compileOnly(variantOf(libs.javafx.graphics) { classifier(javafxClassifier) })

	testImplementation(variantOf(libs.javafx.base) { classifier(javafxClassifier) })
	testImplementation(variantOf(libs.javafx.graphics) { classifier(javafxClassifier) })
	testRuntimeOnly(project(path = ":webrtc-jni", configuration = "natives"))
}

publishing {
	publications {
		create<MavenPublication>("maven") {
			from(components["java"])

			pom {
				withXml {
					val dependencies = (asNode().get("dependencies") as groovy.util.NodeList).first() as groovy.util.Node
					val dependency = dependencies.appendNode("dependency")
					dependency.appendNode("groupId", "org.openjfx")
					dependency.appendNode("artifactId", "javafx-graphics")
					dependency.appendNode("version", libs.versions.javafx.get())
					dependency.appendNode("optional", "true")
				}
			}
		}
	}
}
