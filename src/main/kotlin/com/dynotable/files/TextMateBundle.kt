package com.dynotable.files

import com.intellij.openapi.application.PathManager
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider.PluginBundle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** TextMate reads a bundle from a directory, not from the plugin jar, so the bundle is copied out first. */
class TextMateBundle : TextMateBundleProvider {
  override fun getBundles(): List<PluginBundle> = listOf(PluginBundle("DynoTable", extract()))

  private fun extract(): Path {
    val dir = Path.of(PathManager.getSystemPath(), "dynotable", "textmate")
    for (name in FILES) {
      val target = dir.resolve(name)
      Files.createDirectories(target.parent)
      val source = checkNotNull(javaClass.getResourceAsStream("/textmate/dynotable/$name")) { "missing resource $name" }
      source.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
    }
    return dir
  }

  private companion object {
    val FILES = listOf("package.json", "syntaxes/partiql.tmLanguage.json", "syntaxes/opensearch.tmLanguage.json")
  }
}
