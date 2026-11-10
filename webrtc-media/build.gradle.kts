plugins {
	`java-library`
	`maven-publish`
	signing
	alias(libs.plugins.nmcp)
}

apply(from = rootProject.file("gradle/module-tests.gradle.kts"))

val hostPlatform = rootProject.extra["hostPlatform"] as String
val platformClassifier = rootProject.extra["platformClassifier"] as String
val buildDate = rootProject.extra["buildDate"] as String
val nativesDir = rootProject.extra["nativesDir"] as File?
@Suppress("UNCHECKED_CAST")
val nativeClassifiers = rootProject.extra["nativeClassifiers"] as List<String>

description = "Media extension for webrtc-java. Reads and decodes media files and network " +
		"streams with FFmpeg and feeds them into a peer connection."

val userHome: String = System.getProperty("user.home")
val ffmpegVersion = providers.gradleProperty("ffmpeg.version")
// Where the compiled FFmpeg libraries are installed and looked for. A build
// reuses an existing install instead of building again, the way the WebRTC
// checkout is reused.
val ffmpegInstallDir = providers.gradleProperty("ffmpeg.install.dir").orElse("$userHome/ffmpeg/$platformClassifier")
// Where webrtc-jni installed WebRTC, and with it the libc++ that its
// toolchain files expect. Same default as webrtc-jni's own.
val webrtcInstallDir = providers.gradleProperty("webrtc.install.dir").orElse("$userHome/webrtc/build")
val cmakeBuildType = providers.gradleProperty("cmake.build.type").orElse("Release")
// One more argument for the CMake configuration, such as a definition. It
// comes last, so it wins over an argument of the same name below.
val cmakeOptions = providers.gradleProperty("cmake.options")

// This module links only against FFmpeg's C ABI and the JVM, so on Windows
// and macOS a build for the host needs no toolchain file. Linux builds and
// cross builds go through webrtc-jni's toolchain files, so that both native
// libraries of a platform are built for the same target, against the same
// sysroot, and so end up with the same glibc floor. On Linux that also means
// compiling with -nostdinc++ and taking the C++ standard library from where
// webrtc-jni takes it, which is what webrtc.install.dir is for.
val toolchainFile = (rootProject.extra["toolchainFile"] as String)
	.takeUnless { platformClassifier == hostPlatform && !platformClassifier.startsWith("linux") }
	?.let { rootProject.file("webrtc-jni/src/main/cpp/toolchain/$it").absolutePath }
	.orEmpty()

val cmakeSourceDir = layout.projectDirectory.dir("src/main/cpp")
val cmakeBuildDir = layout.buildDirectory.dir("cmake/$platformClassifier/build")
// The CMake build collects the native library, the FFmpeg libraries and their
// licenses next to its build directory.
val nativesStagingDir = layout.buildDirectory.dir("cmake/$platformClassifier/natives")

// As in webrtc-jni, the CMake tasks run with every build that needs the
// libraries, and CMake decides what has to be built again.
val cmakeGenerate = tasks.register<Exec>("cmakeGenerate") {
	description = "Configures the native build. The first run compiles FFmpeg, which takes a while."
	group = "build"
	// On Linux the native build takes libc++ from the WebRTC install, which
	// the JNI build installs.
	dependsOn(":webrtc-jni:cmakeBuild")

	executable = "cmake"

	if (platformClassifier.startsWith("windows")) {
		args("-A", if (platformClassifier == "windows-aarch64") "ARM64" else "x64")
	}

	args("-S", cmakeSourceDir.asFile.absolutePath)
	args("-B", cmakeBuildDir.get().asFile.absolutePath)
	args("-DMEDIA_TOOLCHAIN_FILE=$toolchainFile")
	args("-DWEBRTC_INSTALL_DIR=${webrtcInstallDir.get()}")
	args("-DFFMPEG_INSTALL_DIR=${ffmpegInstallDir.get()}")
	args("-DFFMPEG_VERSION=${ffmpegVersion.get()}")
	args("-DCMAKE_BUILD_TYPE=${cmakeBuildType.get()}")
	args("-DOUTPUT_NAME_SUFFIX=$platformClassifier")

	if (cmakeOptions.isPresent) {
		args(cmakeOptions.get())
	}
}

val cmakeBuild = tasks.register<Exec>("cmakeBuild") {
	description = "Compiles the native library and collects it with the FFmpeg libraries."
	group = "build"
	dependsOn(cmakeGenerate)

	executable = "cmake"
	args("--build", cmakeBuildDir.get().asFile.absolutePath)
	args("--config", cmakeBuildType.get())
	// A job per processor: without a number, Makefile builds start every
	// compile at once, which exhausts the memory of a small machine.
	args("--parallel", Runtime.getRuntime().availableProcessors().toString())
}

// The native library and the FFmpeg shared libraries go into a jar of their
// own, one per platform, exactly as webrtc-java ships its natives. The main
// jar stays platform independent.
val nativeJar = tasks.register<Jar>("nativeJar") {
	description = "Packages the native libraries, the jar published with the platform classifier."
	group = "build"
	dependsOn(cmakeBuild)

	archiveClassifier = platformClassifier

	from(nativesStagingDir)

	manifest {
		attributes(
			"Version" to project.version,
			"Build-Date" to buildDate,
			"FFmpeg-Version" to ffmpegVersion.get(),
			"FFmpeg-License" to "LGPL-2.1-or-later",
		)
	}
}

tasks.assemble {
	dependsOn(nativeJar)
}

apply(from = rootProject.file("gradle/natives.gradle.kts"))

val platformNatives = extra["platformNatives"] as FileCollection

dependencies {
	api(project(":webrtc"))

	testRuntimeOnly(project(path = ":webrtc-jni", configuration = "natives"))
	testRuntimeOnly(platformNatives)
}

tasks.test {
	// The FFmpeg release the native libraries have to be built from, so that
	// a test catches an install left over from an earlier one.
	systemProperty("test.ffmpeg.version", ffmpegVersion.get())
}

// The natives of this module are not a dependency of it: anything using the
// module asks for them itself, with a second dependency carrying the platform
// classifier.
publishing {
	publications {
		create<MavenPublication>("maven") {
			from(components["java"])

			if (nativesDir != null) {
				// A release: the native library jars of every platform, collected
				// from the per-platform builds into one deployment.
				nativeClassifiers.forEach { nativeClassifier ->
					artifact(nativesDir.resolve("webrtc-java-media-${project.version}-$nativeClassifier.jar")) {
						classifier = nativeClassifier
						builtBy(rootProject.tasks.named("checkNatives"))
					}
				}
			}
			else {
				artifact(nativeJar) {
					classifier = platformClassifier
				}
			}
		}
	}
}
