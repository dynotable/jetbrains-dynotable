package com.dynotable.actions

import com.dynotable.connect.Platform
import com.dynotable.files.DynoTableSqlFileType
import com.dynotable.refusals.notify
import com.dynotable.settings.DynoTableProjectSettings
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.roots.ProjectFileIndex

const val OPEN_SQL_FILE = "Open a SQL file to attach it to DynoTable."
const val ALREADY_ATTACHED = "DynoTable already attaches this file."
const val OUTSIDE_PROJECT = "Only a file in the project can be attached. Outside the project, name the file *.dynotable.sql."

/** Adds the `.sql` file in the editor to the project's `sqlFiles`. */
class AttachSqlFileAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val file = e.getData(CommonDataKeys.EDITOR)?.virtualFile
    if (file == null || !"sql".equals(file.extension, ignoreCase = true)) {
      notify(project, OPEN_SQL_FILE, NotificationType.INFORMATION)
      return
    }
    if (file.fileType == DynoTableSqlFileType) {
      notify(project, ALREADY_ATTACHED, NotificationType.INFORMATION)
      return
    }
    val settings = DynoTableProjectSettings.getInstance(project)
    // The overrider serves only files in the project content.
    val baseDir = project.basePath?.takeIf { ProjectFileIndex.getInstance(project).isInContent(file) }
    val sqlFiles = attach(Platform.current(), baseDir, file.path, settings.sqlFiles)
    if (sqlFiles == null) {
      notify(project, OUTSIDE_PROJECT, NotificationType.INFORMATION)
      return
    }
    settings.sqlFiles = sqlFiles
  }
}
