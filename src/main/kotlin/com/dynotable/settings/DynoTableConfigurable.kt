package com.dynotable.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows

/** Settings › Tools › DynoTable. */
class DynoTableConfigurable(private val project: Project) : BoundConfigurable("DynoTable") {
  override fun createPanel(): DialogPanel {
    val projectSettings = DynoTableProjectSettings.getInstance(project)
    val appSettings = DynoTableAppSettings.getInstance()
    return panel {
      group("This Project") {
        row {
          textArea()
            .label("SQL files:", LabelPosition.TOP)
            .comment("Glob patterns of .sql files, one on each line, relative to the project folder. DynoTable serves the .sql files that match. Other .sql files get nothing.")
            .rows(4)
            .align(AlignX.FILL)
            .bindText(
              { projectSettings.sqlFiles.joinToString("\n") },
              { text -> projectSettings.sqlFiles = text.lines().map { it.trim() }.filter { it.isNotEmpty() } },
            )
        }
      }
      group("This Computer") {
        row("App path:") {
          textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFileOrDir())
            .align(AlignX.FILL)
            .comment("The path of the DynoTable app. Leave it empty to use the standard install location.")
            .bindText(appSettings::appPath)
        }
        row("User data directory:") {
          textFieldWithBrowseButton(FileChooserDescriptorFactory.singleDir())
            .align(AlignX.FILL)
            .comment("Set it only when the app runs with a custom user data directory. When it is set, the plugin does not start the app.")
            .bindText(appSettings::userDataDir)
        }
      }
    }
  }
}
