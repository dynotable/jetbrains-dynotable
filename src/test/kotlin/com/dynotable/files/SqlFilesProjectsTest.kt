package com.dynotable.files

import com.dynotable.settings.DynoTableProjectSettings
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.runInEdtAndWait
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.writeText

@TestApplication
class SqlFilesProjectsTest {
  private val dirA = tempPathFixture()
  private val a = projectFixture(dirA, openAfterCreation = true)
  private val moduleA = a.moduleFixture(dirA, addPathToSourceRoot = true)
  private val dirB = tempPathFixture()
  private val b = projectFixture(dirB, openAfterCreation = true)
  private val moduleB = b.moduleFixture(dirB, addPathToSourceRoot = true)

  // A file in a directory the VFS has not seen yet can come back null on the first refresh: refresh the
  // directory first, and look the file up again once.
  private fun write(path: Path): VirtualFile {
    path.writeText("SELECT 1")
    val fs = LocalFileSystem.getInstance()
    fs.refreshAndFindFileByNioFile(path.parent)?.refresh(false, false)
    return fs.refreshAndFindFileByNioFile(path) ?: fs.refreshAndFindFileByNioFile(path)!!
  }

  @Test
  fun `a project's sqlFiles switches only the sql files of its own content`() = runInEdtAndWait {
    val inA = write(dirA.get().resolve("q.sql"))
    val inB = write(dirB.get().resolve("q.sql"))
    DynoTableProjectSettings.getInstance(a.get()).sqlFiles = listOf("**/*.sql")
    assertEquals(DynoTableSqlFileType, inA.fileType)
    assertNotEquals(DynoTableSqlFileType, inB.fileType)
  }

  // A VCS update of .idea/dynotable.xml loads the state again; the platform caches each file's type until a file types change.
  @Test
  fun `a reloaded sqlFiles state switches a matching sql file at once`() = runInEdtAndWait {
    val file = write(dirA.get().resolve("q.sql"))
    assertNotEquals(DynoTableSqlFileType, file.fileType)
    DynoTableProjectSettings.getInstance(a.get()).loadState(DynoTableProjectSettings.State().apply { sqlFiles = mutableListOf("*.sql") })
    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    assertEquals(DynoTableSqlFileType, file.fileType)
  }
}
