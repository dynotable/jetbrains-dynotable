package com.dynotable.files

import com.dynotable.settings.DynoTableProjectSettings
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.runInEdtAndWait
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The TextMate scopes of each token, as the editor colours them. A file type with no grammar gives
 * plain tokens with no scope; a missing editor highlighter provider throws when the editor opens.
 */
@TestApplication
class HighlightingTest {
  private val tempDir = tempPathFixture()
  private val project = projectFixture(tempDir, openAfterCreation = true)
  // Gives the project a content root: the overrider switches only files in project content.
  private val module = project.moduleFixture(tempDir, addPathToSourceRoot = true)
  private val fixture by codeInsightFixture(project, tempDir)

  private fun scopes(file: VirtualFile): List<String> {
    fixture.openFileInEditor(file)
    val iterator = (fixture.editor as EditorEx).highlighter.createIterator(0)
    return buildList {
      while (!iterator.atEnd()) {
        add(iterator.tokenType.toString())
        iterator.advance()
      }
    }
  }

  // The bundle is copied out of the jar at run time, and the OpenSearch grammar only includes the
  // JSON-with-comments grammar of the IDE's own JSON bundle: each link can fail with no error.
  @Test
  fun `each own file type gets the colours of its grammar`() = runInEdtAndWait {
    val partiql = fixture.configureByText("q.partiql", "SELECT * FROM t").virtualFile
    assertEquals(PartiQLFileType, partiql.fileType)
    assertTrue("source.partiql keyword.other.DML.sql" in scopes(partiql), "${scopes(partiql)}")

    val openSearch = fixture.configureByText("q.opensearch", """{"query": {}}""").virtualFile
    assertEquals(OpenSearchFileType, openSearch.fileType)
    val openSearchScopes = scopes(openSearch)
    assertTrue(openSearchScopes.any { it.startsWith("source.opensearch ") && it.endsWith(" support.type.property-name.json.comments") }, "$openSearchScopes")

    val sql = fixture.configureByText("q.dynotable.sql", "SELECT * FROM t").virtualFile
    assertEquals(DynoTableSqlFileType, sql.fileType)
    assertTrue("source.sql keyword.other.DML.sql" in scopes(sql), "${scopes(sql)}")
  }

  // The platform caches a file's type: without a file types change, a new pattern does nothing until restart.
  @Test
  fun `a sqlFiles pattern switches a matching sql file to DynoTable SQL at once`() = runInEdtAndWait {
    val matching = fixture.addFileToProject("db/q.sql", "SELECT * FROM t").virtualFile
    val other = fixture.addFileToProject("other.sql", "SELECT * FROM t").virtualFile
    assertNotEquals(DynoTableSqlFileType, matching.fileType)

    DynoTableProjectSettings.getInstance(project.get()).sqlFiles = listOf("db/*.sql")

    assertEquals(DynoTableSqlFileType, matching.fileType)
    assertNotEquals(DynoTableSqlFileType, other.fileType)
    assertTrue("source.sql keyword.other.DML.sql" in scopes(matching), "${scopes(matching)}")
  }
}
