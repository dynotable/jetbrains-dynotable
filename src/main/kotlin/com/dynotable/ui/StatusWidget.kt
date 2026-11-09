package com.dynotable.ui

import com.dynotable.connect.ConnectCore
import com.dynotable.settings.DynoTableWorkspaceSettings
import com.dynotable.status.Action
import com.dynotable.status.Status
import com.dynotable.status.actions
import com.dynotable.status.text
import com.dynotable.status.tooltip
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

class StatusWidgetFactory : StatusBarWidgetFactory {
  override fun getId() = StatusWidget.ID

  override fun getDisplayName() = "DynoTable"

  override fun createWidget(project: Project) = StatusWidget(project)
}

/** The status: the profile or the reason. A click shows the actions of the status. */
class StatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
  // Every project has the widget. It must not start the connection, and its port-file watcher, itself.
  private val status get() = project.getServiceIfCreated(ConnectCore::class.java)?.status ?: Status.Hidden

  override fun ID() = ID

  override fun getPresentation() = this

  private val requested get() = DynoTableWorkspaceSettings.getInstance(project).profile?.name

  override fun getText() = status.text(requested)

  override fun getTooltipText() = status.tooltip(requested)

  override fun getAlignment() = Component.LEFT_ALIGNMENT

  override fun getClickConsumer() = Consumer<MouseEvent> { showActions() }

  fun showActions() {
    val current = status
    showChoice(project, (current as? Status.Disconnected)?.reason?.sentence, current.actions, Action::label) { action ->
      action?.let(ConnectCore.getInstance(project)::perform)
    }
  }

  companion object {
    const val ID = "DynoTable.Status"
  }
}
