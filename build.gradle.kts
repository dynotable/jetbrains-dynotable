import org.jetbrains.intellij.platform.gradle.TestFrameworkType

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
}

val patchedPluginXml = layout.buildDirectory.file("tmp/patchPluginXml/plugin.xml")

tasks.test {
  useJUnitPlatform { excludeTags("live") }
  dependsOn(tasks.patchPluginXml)
  systemProperty("dynotable.patchedPluginXml", patchedPluginXml.get().asFile.absolutePath)
  systemProperty("dynotable.projectDir", layout.projectDirectory.asFile.absolutePath)
  // Tests read these files from dynotable.projectDir; without the inputs, a change leaves the test task up to date.
  inputs.files("README.md", "PROTOCOL.md").withPathSensitivity(PathSensitivity.RELATIVE)
  inputs.dir("contract").withPathSensitivity(PathSensitivity.RELATIVE)
  inputs.dir("scripts").withPathSensitivity(PathSensitivity.RELATIVE)
}

val testLive by tasks.registering(Test::class) {
  description = "Runs the @Tag(\"live\") tests against a prepared app. Local only."
  group = "verification"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform { includeTags("live") }
}
