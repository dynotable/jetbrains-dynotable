package com.dynotable.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopupStepEx
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.ui.StatusText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A list popup: the chosen item, or `null` when the user closes it. [text] and [detail] (grey
 * secondary text) show as given: pass server text through [plain].
 */
suspend fun <T> choose(
  project: Project,
  title: String,
  items: List<T>,
  text: (T) -> String,
  detail: (T) -> String? = { null },
  selectable: (T) -> Boolean = { true },
): T? {
  val chosen = CompletableDeferred<T?>()
  withContext(Dispatchers.EDT) {
    val step = object : BaseListPopupStep<T>(title, items), ListPopupStepEx<T> {
      override fun getTextFor(value: T) = text(value)

      override fun getSecondaryTextFor(value: T) = detail(value)

      override fun getTooltipTextFor(value: T): String? = null

      override fun setEmptyText(emptyText: StatusText) {}

      override fun isSelectable(value: T) = selectable(value)

      override fun onChosen(selectedValue: T, finalChoice: Boolean): PopupStep<*>? {
        chosen.complete(selectedValue)
        return FINAL_CHOICE
      }

      override fun canceled() {
        chosen.complete(null)
      }
    }
    JBPopupFactory.getInstance().createListPopup(step).showCenteredInCurrentWindow(project)
  }
  return chosen.await()
}

// A popup row is a JLabel, which renders a text that starts with `<html>` as HTML.
fun plain(text: String) = if (text.startsWith("<html>", ignoreCase = true)) "​$text" else text
