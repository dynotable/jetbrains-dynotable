package com.dynotable.ui

import com.dynotable.connect.ConnectCore
import com.dynotable.settings.DynoTableWorkspaceSettings
import com.dynotable.status.Action
import com.dynotable.status.Status
import com.dynotable.status.actions
import com.dynotable.status.text
import com.dynotable.status.tooltip
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
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
  private val core get() = ConnectCore.getInstance(project)

  override fun ID() = ID

  override fun getPresentation() = this

  private val requested get() = DynoTableWorkspaceSettings.getInstance(project).profile?.name

  override fun getText() = core.status.text(requested)

  override fun getTooltipText() = core.status.tooltip(requested)

  override fun getAlignment() = Component.LEFT_ALIGNMENT

  override fun getClickConsumer() = Consumer<MouseEvent> { showActions() }

  fun showActions() {
    val status = core.status
    val title = (status as? Status.Disconnected)?.reason?.sentence
    val step = object : BaseListPopupStep<Action>(title, status.actions) {
      override fun getTextFor(value: Action) = value.label

      override fun onChosen(selectedValue: Action, finalChoice: Boolean): PopupStep<*>? {
        core.perform(selectedValue)
        return FINAL_CHOICE
      }
    }
    JBPopupFactory.getInstance().createListPopup(step).showCenteredInCurrentWindow(project)
  }

  companion object {
    const val ID = "DynoTable.Status"
  }
}
