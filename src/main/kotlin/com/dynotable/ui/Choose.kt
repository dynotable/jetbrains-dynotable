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
  withContext(Dispatchers.EDT) { showChoice(project, title, items, text, detail, selectable) { chosen.complete(it) } }
  return chosen.await()
}

/** As [choose], for a caller on the EDT: [done] gets the chosen item, or `null` when the user closes the popup. */
fun <T> showChoice(
  project: Project,
  title: String?,
  items: List<T>,
  text: (T) -> String,
  detail: (T) -> String? = { null },
  selectable: (T) -> Boolean = { true },
  done: (T?) -> Unit,
) {
  val step = object : BaseListPopupStep<T>(title, items), ListPopupStepEx<T> {
    override fun getTextFor(value: T) = text(value)

    override fun getSecondaryTextFor(value: T) = detail(value)

    override fun getTooltipTextFor(value: T): String? = null

    override fun setEmptyText(emptyText: StatusText) {}

    override fun isSelectable(value: T) = selectable(value)

    override fun onChosen(selectedValue: T, finalChoice: Boolean): PopupStep<*>? {
      done(selectedValue)
      return FINAL_CHOICE
    }

    override fun canceled() {
      done(null)
    }
  }
  JBPopupFactory.getInstance().createListPopup(step).showCenteredInCurrentWindow(project)
}

private const val ZERO_WIDTH_SPACE = '\u200B'

// A popup row is a JLabel, which renders a text that starts with `<html>` as HTML.
fun plain(text: String) = if (text.startsWith("<html>", ignoreCase = true)) "$ZERO_WIDTH_SPACE$text" else text
