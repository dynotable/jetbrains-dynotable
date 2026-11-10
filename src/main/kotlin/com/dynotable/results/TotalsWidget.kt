package com.dynotable.results

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import java.awt.Component

class TotalsWidgetFactory : StatusBarWidgetFactory {
  override fun getId() = TotalsWidget.ID

  override fun getDisplayName() = "DynoTable Totals"

  override fun createWidget(project: Project) = TotalsWidget(project)
}

/** The totals of the selected number cells in DynoTable Results, in the status bar, as the IDE's data grids show them. */
class TotalsWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
  override fun ID() = ID

  override fun getPresentation() = this

  // Every project has the widget; it must not create the results service itself.
  override fun getText() = project.getServiceIfCreated(Results::class.java)?.totals.orEmpty()

  override fun getTooltipText() = "Totals of the selected cells in DynoTable Results"

  override fun getAlignment() = Component.LEFT_ALIGNMENT

  companion object {
    const val ID = "DynoTable.Totals"

    fun update(project: Project) {
      WindowManager.getInstance().getStatusBar(project)?.updateWidget(ID)
    }
  }
}
