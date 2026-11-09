package com.dynotable.actions

import com.dynotable.harness.harnessFixture
import com.dynotable.harness.waitFor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.time.Duration.Companion.minutes

@TestApplication
@DisabledOnOs(OS.WINDOWS)
class AttachSqlFileTest {
  private val tempDir = tempPathFixture()
  private val project = projectFixture(tempDir, openAfterCreation = true)
  private val module = project.moduleFixture(tempDir, addPathToSourceRoot = true)
  private val fixture by codeInsightFixture(project, tempDir)
  private val h by harnessFixture(project)

  // Unescaped, the pattern is a character class that never matches the file, so the file stays unserved.
  @Test
  fun `Attach SQL File on a bracketed name makes the open file served`() = timeoutRunBlocking(2.minutes) {
    h.fake.start()
    withContext(Dispatchers.EDT) {
      fixture.configureByText("[prod].sql", "SELECT * FROM t")
      val context = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project.get())
        .add(CommonDataKeys.EDITOR, fixture.editor)
        .build()
      ActionUtil.invokeAction(ActionManager.getInstance().getAction("DynoTable.AttachSqlFile"), context, "test", null, null)
    }
    waitFor("didOpen of the attached file") { h.fake.openedUri("%5Bprod%5D.sql")?.let { h.fake.language(it) } == "sql" }
  }
}
