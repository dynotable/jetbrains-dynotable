package com.dynotable.files

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object DynoTableIcons {
  @JvmField val FILE: Icon = IconLoader.getIcon("/icons/dynotable.svg", DynoTableIcons::class.java)
}

// Prefixed ids: another plugin can register a plain "PartiQL" or "OpenSearch" language.
object DynoTableSqlLanguage : Language("DynoTableSQL") {
  private fun readResolve(): Any = DynoTableSqlLanguage
  override fun getDisplayName() = "DynoTable SQL"
}

object PartiQLLanguage : Language("DynoTablePartiQL") {
  private fun readResolve(): Any = PartiQLLanguage
  override fun getDisplayName() = "PartiQL"
}

object OpenSearchLanguage : Language("DynoTableOpenSearch") {
  private fun readResolve(): Any = OpenSearchLanguage
  override fun getDisplayName() = "OpenSearch"
}

abstract class DynoTableFileType(
  language: Language,
  private val name: String,
  private val description: String,
  private val extension: String,
) : LanguageFileType(language) {
  override fun getName() = name
  override fun getDescription() = description
  override fun getDefaultExtension() = extension
  override fun getIcon() = DynoTableIcons.FILE
}

object DynoTableSqlFileType : DynoTableFileType(DynoTableSqlLanguage, "DynoTable SQL", "DynoTable SQL query", "dynotable.sql")

object PartiQLFileType : DynoTableFileType(PartiQLLanguage, "DynoTable PartiQL", "DynoTable PartiQL query", "partiql")

object OpenSearchFileType : DynoTableFileType(OpenSearchLanguage, "DynoTable OpenSearch", "DynoTable OpenSearch query", "opensearch")
