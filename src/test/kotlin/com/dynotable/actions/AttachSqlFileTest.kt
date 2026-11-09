package com.dynotable.actions

import com.dynotable.harness.harnessFixture
import com.dynotable.settings.DynoTableProjectSettings
import com.dynotable.harness.waitFor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

@TestApplication
@DisabledOnOs(OS.WINDOWS)
class AttachSqlFileTest {
  private val tempDir = tempPathFixture()
  private val project = projectFixture(tempDir, openAfterCreation = true)
  private val module = project.moduleFixture(tempDir, addPathToSourceRoot = true)
  private val fixture by codeInsightFixture(project, tempDir)
  private val h by harnessFixture(project)

  /** Runs Attach SQL File on the fixture's editor, as the Tools menu does. */
  private fun attach() {
    val context = SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, project.get())
      .add(CommonDataKeys.EDITOR, fixture.editor)
      .build()
    ActionUtil.invokeAction(ActionManager.getInstance().getAction("DynoTable.AttachSqlFile"), context, "test", null, null)
  }

  // Unescaped, the pattern is a character class that never matches the file, so the file stays unserved.
  @Test
  fun `Attach SQL File on a bracketed name makes the open file served`() = timeoutRunBlocking(2.minutes) {
    h.fake.start()
    withContext(Dispatchers.EDT) {
      fixture.configureByText("[prod].sql", "SELECT * FROM t")
      attach()
    }
    waitFor("didOpen of the attached file") { h.fake.openedUri("%5Bprod%5D.sql")?.let { h.fake.language(it) } == "sql" }
  }

  // A pattern for a file the overrider never serves, or for one it already serves, would change nothing but restart the session.
  @Test
  fun `Attach SQL File tells why it attaches nothing, and changes no pattern`() = timeoutRunBlocking(2.minutes) {
    h.fake.start()
    val outside = h.appRoot.resolveSibling("outside.sql").apply { writeText("SELECT 1") }
    withContext(Dispatchers.EDT) {
      fixture.configureByText("notes.txt", "x")
      attach()
      fixture.configureByText("q.dynotable.sql", "SELECT 1")
      attach()
      fixture.openFileInEditor(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(outside)!!)
      attach()
    }
    assertEquals(listOf(OPEN_SQL_FILE, ALREADY_ATTACHED, OUTSIDE_PROJECT), h.sentences())
    assertEquals(emptyList<String>(), DynoTableProjectSettings.getInstance(project.get()).sqlFiles)
  }
}
