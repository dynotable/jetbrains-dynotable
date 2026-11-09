package com.dynotable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/** Checks the plugin.xml that the build ships (after `patchPluginXml`), and the README it must match. */
class ManifestTest {
  private val pluginXml: Element = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    .parse(Path.of(System.getProperty("dynotable.patchedPluginXml")).toFile()).documentElement

  private val readme: String = Files.readString(Path.of(System.getProperty("dynotable.projectDir"), "README.md"))

  private fun elements(tag: String) = pluginXml.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

  // Without it, IDEs that have no LSP API (for example Android Studio) install the plugin and fail at class load.
  @Test
  fun `depends on the LSP module and on TextMate`() {
    val depends = elements("depends").map { it.textContent.trim() }
    assertTrue("com.intellij.modules.lsp" in depends, "depends: $depends")
    assertTrue("org.jetbrains.plugins.textmate" in depends, "depends: $depends")
  }

  // The README promises a minimum IDE version; since-build must be that version, and no until-build may
  // switch the plugin off in the next major IDE.
  @Test
  fun `since-build is the README minimum version and until-build is open`() {
    val match = Regex("""JetBrains IDE[^\n]*version (\d{4})\.(\d)""").find(readme)
    checkNotNull(match) { "the README names no minimum IDE version" }
    val (year, release) = match.destructured
    val build = "${year.toInt() % 100}${release}"
    val ideaVersion = elements("idea-version").single()
    assertEquals(build, ideaVersion.getAttribute("since-build"))
    assertNull(ideaVersion.getAttributeNode("until-build"), "until-build must stay open")
  }
}
