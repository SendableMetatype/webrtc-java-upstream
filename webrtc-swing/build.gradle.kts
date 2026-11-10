plugins {
	`java-library`
	`maven-publish`
	signing
	alias(libs.plugins.nmcp)
}

apply(from = rootProject.file("gradle/module-tests.gradle.kts"))

description = "Swing extension for webrtc-java. Shows the video of the client API in Swing windows."

dependencies {
	api(project(":webrtc"))

	testRuntimeOnly(project(path = ":webrtc-jni", configuration = "natives"))
}

publishing {
	publications {
		create<MavenPublication>("maven") {
			from(components["java"])
		}
	}
}
