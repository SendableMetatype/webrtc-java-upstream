plugins {
	base
}

val hostPlatform = rootProject.extra["hostPlatform"] as String
val platformClassifier = rootProject.extra["platformClassifier"] as String
val buildDate = rootProject.extra["buildDate"] as String

val userHome: String = System.getProperty("user.home")
val webrtcBranch = providers.gradleProperty("webrtc.branch")
val webrtcSrcDir = providers.gradleProperty("webrtc.src.dir").orElse("$userHome/webrtc")
val webrtcInstallDir = providers.gradleProperty("webrtc.install.dir").orElse("$userHome/webrtc/build")
val cmakeBuildType = providers.gradleProperty("cmake.build.type").orElse("Release")
// One more argument for the CMake configuration, such as a definition. It
// comes last, so it wins over an argument of the same name below.
val cmakeOptions = providers.gradleProperty("cmake.options")

// An Intel Mac builds for itself with its own compiler and no toolchain file.
// Every other build takes the file of its platform.
val toolchainFile = (rootProject.extra["toolchainFile"] as String)
	.takeUnless { platformClassifier == "macos-x86_64" && hostPlatform == platformClassifier }
	?.let { "toolchain/$it" }
	.orEmpty()

val cmakeSourceDir = layout.projectDirectory.dir("src/main/cpp")
val cmakeBuildDir = layout.buildDirectory.dir("cmake/$platformClassifier")
val installDir = layout.buildDirectory.dir("lib/$platformClassifier")

// The CMake tasks declare no inputs and outputs, so they run with every build
// that needs the library, and CMake decides what has to be built again. It
// knows what it reads, also outside this project: the WebRTC checkout and
// install, the toolchain and the sysroot.
val cmakeGenerate = tasks.register<Exec>("cmakeGenerate") {
	description = "Configures the native build. The first run fetches and compiles WebRTC, which takes a while."
	group = "build"

	executable = "cmake"

	if (platformClassifier.startsWith("windows")) {
		args("-A", if (platformClassifier == "windows-aarch64") "ARM64" else "x64")
		args("-T", "ClangCL")
	}

	args("-S", cmakeSourceDir.asFile.absolutePath)
	args("-B", cmakeBuildDir.get().asFile.absolutePath)
	args("-DWEBRTC_TOOLCHAIN_FILE=$toolchainFile")
	args("-DWEBRTC_BRANCH=${webrtcBranch.get()}")
	args("-DWEBRTC_SRC_DIR=${webrtcSrcDir.get()}")
	args("-DWEBRTC_INSTALL_DIR=${webrtcInstallDir.get()}")
	args("-DCMAKE_BUILD_TYPE=${cmakeBuildType.get()}")
	args("-DCMAKE_INSTALL_PREFIX=${installDir.get().asFile.absolutePath}")
	args("-DOUTPUT_NAME_SUFFIX=$platformClassifier")

	if (cmakeOptions.isPresent) {
		args(cmakeOptions.get())
	}
}

val cmakeBuild = tasks.register<Exec>("cmakeBuild") {
	description = "Compiles the native library and installs it into build/lib."
	group = "build"
	dependsOn(cmakeGenerate)

	executable = "cmake"
	args("--build", cmakeBuildDir.get().asFile.absolutePath)
	args("--config", cmakeBuildType.get())
	args("--target", "install")
	// A job per processor: without a number, Makefile builds start every
	// compile at once, which exhausts the memory of a small machine.
	args("--parallel", Runtime.getRuntime().availableProcessors().toString())
}

val nativeJar = tasks.register<Jar>("nativeJar") {
	description = "Packages the native library, the jar published with the platform classifier."
	group = "build"
	dependsOn(cmakeBuild)

	archiveBaseName = "webrtc-java"
	archiveClassifier = platformClassifier
	// The base plugin alone would put the jar into build/distributions.
	destinationDirectory = layout.buildDirectory.dir("libs")

	from(installDir)

	manifest {
		attributes(
			"Automatic-Module-Name" to "webrtc." + platformClassifier.replace('-', '.'),
			"Version" to project.version,
			"Build-Date" to buildDate,
		)
	}
}

tasks.assemble {
	dependsOn(nativeJar)
}

apply(from = rootProject.file("gradle/natives.gradle.kts"))
