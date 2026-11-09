package com.dynotable.files

import com.dynotable.settings.DynoTableProjectSettings
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile

/** A `.sql` file is DynoTable SQL when a `sqlFiles` pattern of an open project whose content holds it matches. */
class SqlFilesOverrider : FileTypeOverrider {
  override fun getOverriddenFileType(file: VirtualFile): FileType? {
    if (file.isDirectory || !"sql".equals(file.extension, ignoreCase = true)) return null
    val projects = ProjectManager.getInstanceIfCreated()?.openProjects ?: return null
    val served = projects.any { project ->
      val baseDir = project.basePath
      !project.isDisposed && baseDir != null &&
        ProjectFileIndex.getInstance(project).isInContent(file) &&
        SqlGlobs.matches(DynoTableProjectSettings.getInstance(project).sqlFiles, baseDir, file.path)
    }
    return if (served) DynoTableSqlFileType else null
  }
}
