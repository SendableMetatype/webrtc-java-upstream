import java.time.Duration
import java.time.Instant

plugins {
	`maven-publish`
	signing
	alias(libs.plugins.nmcp)
	alias(libs.plugins.nmcp.aggregation)
}

description = "Java native interface implementation based on the free, open WebRTC project. " +
		"The goal of this project is to enable development of RTC applications for desktop platforms running Java."

/**
 * The platform of the machine the build runs on, in the form of the artifact
 * classifier: `<os>-<arch>`.
 */
fun hostPlatform(): String {
	val osName = System.getProperty("os.name").lowercase()
	val osArch = System.getProperty("os.arch").lowercase()

	val os = when {
		osName.startsWith("windows") -> "windows"
		osName.startsWith("mac") -> "macos"
		osName.contains("linux") -> "linux"
		else -> throw GradleException("Unsupported operating system: $osName")
	}
	val arch = when (osArch) {
		"amd64", "x86_64", "x86-64" -> "x86_64"
		"aarch64", "arm64" -> "aarch64"
		"arm", "armv7l", "aarch32" -> "aarch32"
		else -> throw GradleException("Unsupported CPU architecture: $osArch")
	}

	return "$os-$arch"
}

// The CMake toolchain file of every platform, in webrtc-jni/src/main/cpp/toolchain.
val toolchainFiles = mapOf(
	"windows-x86_64" to "x86_64-windows-clang.cmake",
	"windows-aarch64" to "aarch64-windows-clang.cmake",
	"linux-x86_64" to "x86_64-linux-clang.cmake",
	"linux-aarch64" to "aarch64-linux-clang.cmake",
	"linux-aarch32" to "aarch32-linux-clang.cmake",
	"macos-x86_64" to "x86_64-macos-cross.cmake",
	"macos-aarch64" to "aarch64-macos-clang.cmake",
)

// The platform the native libraries are built for. Set
// -Pwebrtc.platform=<classifier> to cross compile, for example linux-aarch64;
// the host platform is the default.
val platformClassifier = providers.gradleProperty("webrtc.platform").orElse(provider { hostPlatform() }).get()

if (platformClassifier !in toolchainFiles) {
	throw GradleException("Unknown platform $platformClassifier, expected one of ${toolchainFiles.keys}")
}

// Shared by the subprojects.
extra["hostPlatform"] = hostPlatform()
extra["platformClassifier"] = platformClassifier
extra["toolchainFile"] = toolchainFiles.getValue(platformClassifier)

// OpenJFX publishes its modules once per platform, with the native code of
// that platform. The Java code is the same in all of them, so the JavaFX
// module and the examples compile against those of the build machine, which
// their tests and runs need. There is none for Windows on arm64 and for
// 32-bit ARM.
extra["javafxClassifier"] = hostPlatform().let {
	when (it) {
		"windows-x86_64", "windows-aarch64" -> "win"
		"macos-x86_64" -> "mac"
		"macos-aarch64" -> "mac-aarch64"
		"linux-aarch64" -> "linux-aarch64"
		else -> "linux"
	}
}

// A directory holding prebuilt native library jars, named
// "<artifact>-<version>-<classifier>.jar", in place of the ones the build
// makes: the tests run against the jars of the build platform, and the
// publication attaches the jars of every platform.
extra["nativesDir"] = providers.gradleProperty("natives.dir").map { file(it) }.orNull

// The classifiers of the native library jars a release has to carry.
val nativeClassifiers = toolchainFiles.keys.toList()

extra["nativeClassifiers"] = nativeClassifiers

// Maven Central publishes a version as one bundle, so a publication that
// attaches the jars of natives.dir needs those of every platform.
tasks.register("checkNatives") {
	description = "Checks that natives.dir holds the native library jars of every platform."

	val dir = providers.gradleProperty("natives.dir").map { file(it) }.orNull
	val version = project.version.toString()

	onlyIf("natives.dir is given") { dir != null }
	doLast {
		val missing = listOf("webrtc-java", "webrtc-java-media")
			.flatMap { artifact -> nativeClassifiers.map { "$artifact-$version-$it.jar" } }
			.filter { !dir!!.resolve(it).isFile }

		if (missing.isNotEmpty()) {
			throw GradleException("Missing native library jars in $dir: ${missing.joinToString()}")
		}
	}
}

// The manifests' Build-Date is the time of the commit, not of the build, so
// that the same commit builds into the same Java jars; Gradle already writes
// their entries in a fixed order with fixed times. Outside a Git checkout it
// is the time of the build.
val commitTime = providers.exec {
	commandLine("git", "log", "-1", "--format=%ct")
	isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }.get()

val buildDate = (commitTime.toLongOrNull()?.let(Instant::ofEpochSecond) ?: Instant.now()).toString()

extra["buildDate"] = buildDate

subprojects {
	// webrtc-java for the core project, webrtc-java-media for webrtc-media.
	val artifactName = "webrtc-java" + name.removePrefix("webrtc")

	pluginManager.withPlugin("base") {
		configure<BasePluginExtension> {
			archivesName = artifactName
		}
	}

	tasks.withType<Jar>().configureEach {
		// The native library jars get their licenses from their CMake installs
		// and carry a manifest of their own.
		if (name != "nativeJar") {
			// The project's license and notice go into every Java jar, where
			// they reach those who get the library from Maven Central rather
			// than from this repository.
			metaInf {
				from(rootProject.layout.projectDirectory.file("LICENSE"))
				from(rootProject.layout.projectDirectory.file("NOTICE"))
			}
		}
	}

	pluginManager.withPlugin("java") {
		tasks.withType<JavaCompile>().configureEach {
			options.encoding = "UTF-8"
			options.isDeprecation = true
			// The libraries target Java 11. The examples need 17 and say so.
			options.release = 11
			// The modules carry the project version, as their jars' manifests do.
			options.javaModuleVersion = provider { project.version.toString() }
		}

		// JUnit 6 needs Java 17. The tests are compiled for Java 11 like the
		// libraries, but they run on the JDK of the build, which is 17 or
		// later, so their dependencies may ask for that.
		listOf("testCompileClasspath", "testRuntimeClasspath").forEach { classpath ->
			configurations.named(classpath) {
				attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
			}
		}

		dependencies {
			"testImplementation"(platform(libs.junit.bom))
			"testImplementation"(libs.junit.jupiter.api)
			"testRuntimeOnly"(libs.junit.jupiter.engine)
			"testRuntimeOnly"(libs.junit.platform.launcher)
		}

		tasks.withType<Test>().configureEach {
			useJUnitPlatform()

			// Opt-in JNI check, with -Pjni-check: -Xcheck:jni validates the
			// references of every JNI call and aborts the JVM with "FATAL ERROR
			// in native method" on a real violation, such as a local reference
			// that was deleted twice. It also warns with "WARNING in native
			// method" about a JNI call made while the exception the previous
			// one may have thrown was left unchecked. Not part of the default
			// test run, since every JNI call gets slower.
			if (providers.gradleProperty("jni-check").isPresent) {
				jvmArgs("-Xcheck:jni")
			}

			// The tests load the native libraries of the machine they run on.
			// Both platforms are inputs, so that a change of one runs the
			// check again instead of leaving the task up to date.
			inputs.property("platform", platformClassifier)
			inputs.property("hostPlatform", hostPlatform())
			doFirst {
				if (platformClassifier != hostPlatform()) {
					throw GradleException("The tests need the native libraries of ${hostPlatform()}, but " +
							"webrtc.platform is $platformClassifier. Build for another platform with the assemble task.")
				}
			}

			// A hung test JVM fails the build instead of blocking a CI runner.
			timeout = Duration.ofMinutes(30)

			testLogging {
				events("passed", "skipped", "failed")
			}
		}
	}

	// What the published Java modules have in common. The examples are not
	// published and are left out.
	pluginManager.withPlugin("maven-publish") {
		configure<JavaPluginExtension> {
			withSourcesJar()
			withJavadocJar()
		}

		tasks.named<Jar>("jar") {
			manifest {
				attributes(
					"Version" to project.version,
					"Build-Date" to buildDate,
				)
			}
		}

		val pomProperties = tasks.register("pomProperties") {
			description = "Writes the pom.properties packaged into the jars."

			val file = layout.buildDirectory.file("maven/pom.properties")
			val content = "artifactId=$artifactName\ngroupId=${project.group}\nversion=${project.version}\n"

			inputs.property("content", content)
			outputs.file(file)

			doLast {
				file.get().asFile.writeText(content)
			}
		}

		// The Maven descriptor, as tools reading it from jars expect.
		listOf("jar", "sourcesJar").forEach { jarName ->
			tasks.named<Jar>(jarName) {
				into("META-INF/maven/${project.group}/$artifactName") {
					from(tasks.withType<GenerateMavenPom>()) {
						rename { "pom.xml" }
					}
					from(pomProperties)
				}
			}
		}

		tasks.named<Javadoc>("javadoc") {
			title = "$artifactName ${project.version} API"

			(options as StandardJavadocDocletOptions).apply {
				encoding = "UTF-8"
				author(true)
				version(true)
				use(true)
				addStringOption("Xdoclint:none", "-quiet")
			}

			isFailOnError = false
		}

		// Maven consumers get the native library of their platform through the
		// parent pom, see below. Gradle module metadata would bypass the pom.
		tasks.withType<GenerateModuleMetadata>().configureEach {
			enabled = false
		}

		configure<PublishingExtension> {
			publications.withType<MavenPublication>().configureEach {
				artifactId = artifactName

				pom {
					name = artifactName
					description = provider { project.description }

					withXml {
						val parent = groovy.util.Node(null, "parent")
						parent.appendNode("groupId", project.group)
						parent.appendNode("artifactId", "webrtc-java-parent")
						parent.appendNode("version", project.version)

						@Suppress("UNCHECKED_CAST")
						(asNode().children() as MutableList<Any>).add(1, parent)
					}
				}
			}
		}
	}
}

allprojects {
	pluginManager.withPlugin("signing") {
		// Releases are signed with the key from the environment; without a key
		// nothing is signed, which is what local and snapshot builds want.
		val signingKey = providers.environmentVariable("MAVEN_GPG_PRIVATE_KEY")
		val signingPassphrase = providers.environmentVariable("MAVEN_GPG_PASSPHRASE")

		if (signingKey.isPresent) {
			configure<SigningExtension> {
				useInMemoryPgpKeys(signingKey.get(), signingPassphrase.getOrElse(""))
				sign(the<PublishingExtension>().publications)
			}
		}
	}
}

/*
 * The parent pom. Its profiles set "platform.classifier" for the operating
 * system and architecture of the machine the pom is evaluated on, and the
 * webrtc-java pom depends on itself with that classifier, so Maven consumers
 * get the native library of their platform without declaring it.
 *
 * The profile for Windows on arm64 has no activation.
 */
val platformProfiles = listOf(
	Triple("windows-x86_64", "windows", "amd64"),
	Triple("windows-aarch64", null, null),
	Triple("linux-x86_64", "linux", "amd64"),
	Triple("linux-aarch32", "linux", "aarch32"),
	Triple("linux-aarch64", "linux", "aarch64"),
	Triple("macos-x86_64", "mac", "x86_64"),
	Triple("macos-aarch64", "mac", "aarch64"),
)

publishing {
	publications {
		create<MavenPublication>("parent") {
			artifactId = "webrtc-java-parent"

			pom {
				packaging = "pom"
				name = "webrtc-java-parent"
				description = project.description
				url = "https://github.com/devopvoid/webrtc-java"

				developers {
					developer {
						id = "a.andres"
						name = "Alex Andres"
						email = "andres.alex@pm.me"
					}
				}
				licenses {
					license {
						name = "The Apache Software License, Version 2.0"
						url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
						distribution = "repo"
					}
				}
				issueManagement {
					system = "GitHub"
					url = "https://github.com/devopvoid/webrtc-java/issues"
				}
				scm {
					connection = "scm:git:git://github.com/devopvoid/webrtc-java.git"
					developerConnection = "scm:git:ssh://git@github.com/devopvoid/webrtc-java.git"
					url = "https://github.com/devopvoid/webrtc-java/tree/main"

					// A release names the tag it is made from.
					if (!project.version.toString().endsWith("-SNAPSHOT")) {
						tag = "v${project.version}"
					}
				}

				withXml {
					val profiles = asNode().appendNode("profiles")

					platformProfiles.forEach { (classifier, osFamily, osArch) ->
						val profile = profiles.appendNode("profile")
						profile.appendNode("id", classifier)

						if (osFamily != null) {
							val os = profile.appendNode("activation").appendNode("os")
							os.appendNode("family", osFamily)
							os.appendNode("arch", osArch)
						}

						profile.appendNode("properties").appendNode("platform.classifier", classifier)
					}
				}
			}
		}
	}
}

// One deployment for Maven Central, which publishes a version as one bundle.
// The JNI project and the examples are not published.
dependencies {
	nmcpAggregation(project(":"))
	nmcpAggregation(project(":webrtc"))
	nmcpAggregation(project(":webrtc-media"))
	nmcpAggregation(project(":webrtc-swing"))
	nmcpAggregation(project(":webrtc-javafx"))
}

nmcpAggregation {
	centralPortal {
		username = providers.environmentVariable("MAVEN_USERNAME")
		password = providers.environmentVariable("MAVEN_TOKEN")
		// A release waits in the Central Portal until it is published there.
		publishingType = "USER_MANAGED"
		publicationName = "${project.group}:webrtc-java:${project.version}"
	}
}
