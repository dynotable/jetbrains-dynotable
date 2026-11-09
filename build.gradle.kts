import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
  id("org.jetbrains.kotlin.jvm") version "2.4.21"
  id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

kotlin { jvmToolchain(21) }

dependencies {
  intellijPlatform {
    intellijIdea(providers.gradleProperty("platformVersion"))
    bundledPlugin("org.jetbrains.plugins.textmate")
    testFramework(TestFrameworkType.Platform)
    testFramework(TestFrameworkType.JUnit5)
    testFramework(TestFrameworkType.JUnit5CodeInsight)
    testFramework(TestFrameworkType.Plugin.LSP)
    pluginVerifier()
    zipSigner()
  }
  // The platform test framework of 2026.3 needs the JUnit 6 runtime.
  testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.opentest4j:opentest4j:1.3.0")
  // Virtual time for the connect rules. The IDE brings its own coroutines core of the same version.
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0") {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-bom")
  }
}

intellijPlatform {
  pluginConfiguration {
    version = providers.gradleProperty("pluginVersion")
    ideaVersion {
      sinceBuild = providers.gradleProperty("pluginSinceBuild")
      untilBuild = provider { null }
    }
  }
  buildSearchableOptions = false
  pluginVerification {
    ides {
      // The minimum version, and the latest release of each IDE.
      listOf(IntelliJPlatformType.IntellijIdea, IntelliJPlatformType.DataGrip).forEach { create(it, providers.gradleProperty("platformVersion")) }
      latest {
        types = listOf(IntelliJPlatformType.IntellijIdea, IntelliJPlatformType.DataGrip)
        channels = listOf(ProductRelease.Channel.RELEASE)
      }
    }
  }
  signing {
    certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
    privateKey = providers.environmentVariable("PRIVATE_KEY")
    password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
  }
  publishing {
    token = providers.environmentVariable("PUBLISH_TOKEN")
  }
}

val patchedPluginXml = layout.buildDirectory.file("tmp/patchPluginXml/plugin.xml")

// The IntelliJ Platform Gradle Plugin sets up the IDE only for `test` (and its own `TestIdeTask`), not
// for another `Test` task. So `testLive` runs `test` with the live tests only.
val requested = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
val live = "testLive" in requested
require(!live || "gate" !in requested) { "Run gate and testLive apart: gate never runs the live tests." }

tasks.test {
  useJUnitPlatform { if (live) includeTags("live") else excludeTags("live") }
  if (live) {
    // The app's state is not an input: a live run never comes from the cache.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    // Read through the providers, so that the configuration cache sees a change.
    listOf("USER_DATA_DIR", "APP_PATH", "PROFILE", "LARGE_TABLE", "DENY_USER_DATA_DIR").map { "DYNOTABLE_LIVE_$it" }.forEach { name ->
      providers.environmentVariable(name).orNull?.let { environment(name, it) }
    }
  }
  dependsOn(tasks.patchPluginXml)
  systemProperty("dynotable.patchedPluginXml", patchedPluginXml.get().asFile.absolutePath)
  systemProperty("dynotable.projectDir", layout.projectDirectory.asFile.absolutePath)
  // Tests read these files from dynotable.projectDir; without the inputs, a change leaves the test task up to date.
  inputs.files("README.md", "PROTOCOL.md").withPathSensitivity(PathSensitivity.RELATIVE)
  inputs.dir("contract").withPathSensitivity(PathSensitivity.RELATIVE)
  inputs.dir("scripts").withPathSensitivity(PathSensitivity.RELATIVE)
  // New Query writes scratch files. A space in the path, as in the macOS config dir, makes the encoded `didOpen` URI differ from the path.
  val scratches = layout.buildDirectory.dir("tmp/test scratches").get().asFile
  systemProperty("idea.scratch.path", scratches.absolutePath)
  doFirst { scratches.deleteRecursively() }
}

tasks.register("testLive") {
  description = "Runs only the @Tag(\"live\") tests, against a prepared app. Local only."
  group = "verification"
  dependsOn(tasks.test)
}

tasks.register("gate") {
  description = "Build, unit and integration tests, plugin verification. No live tests; the drift check is its own CI job."
  group = "verification"
  dependsOn(tasks.build, tasks.verifyPlugin)
}
