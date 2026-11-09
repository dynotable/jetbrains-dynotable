package com.dynotable.query

import com.dynotable.connect.ConnectCore
import com.dynotable.editor.ServerCommands
import com.dynotable.explorer.indexFailures
import com.dynotable.files.OpenSearchFileType
import com.dynotable.protocol.LIST_INDICES
import com.dynotable.protocol.ListIndicesResult
import com.dynotable.protocol.ProtocolException
import com.dynotable.refusals.UNREADABLE
import com.dynotable.refusals.notify
import com.dynotable.ui.choose
import com.dynotable.ui.plain
import com.google.gson.JsonObject
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val NO_INDEX = "DynoTable lists no OpenSearch index."

/** Writes the chosen index into line 0 of the OpenSearch query in the editor. */
class PickIndexAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR)?.virtualFile?.fileType == OpenSearchFileType
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    IndexPicker.getInstance(project).launch(e.getData(CommonDataKeys.EDITOR)?.document ?: return)
  }
}

@Service(Service.Level.PROJECT)
class IndexPicker(private val project: Project, private val cs: CoroutineScope) {
  private val core get() = ConnectCore.getInstance(project)

  /** For a caller on the EDT, which must not wait. */
  fun launch(document: Document) {
    cs.launch { pick(document) }
  }

  suspend fun pick(document: Document) {
    val client = core.client() ?: return
    val session = core.session() ?: return
    val answer = ServerCommands.getInstance(project).custom(client, LIST_INDICES) { it.listIndices(JsonObject()) } ?: return
    val listing = try {
      ListIndicesResult.from(answer.value)
    } catch (_: ProtocolException) {
      notify(project, UNREADABLE, NotificationType.ERROR)
      return
    }
    if (listing.indices.isEmpty()) {
      notify(project, indexFailures(listing).joinToString(" ").ifEmpty { NO_INDEX }, NotificationType.WARNING)
      return
    }
    val row = choose(project, "Select an OpenSearch index", listing.indices, { plain(it.name) }, { plain(it.target) }) ?: return
    // A replaced session can list the indices of another profile.
    if (core.session() != session) return
    when (val pin = pinFor(row, listing)) {
      is Unpinnable -> notify(project, pin.warning, NotificationType.WARNING)
      is Pin -> withContext(Dispatchers.EDT) {
        WriteCommandAction.runWriteCommandAction(project, "Pick Index", null, {
          // The session can change while the write waits for the EDT.
          if (core.session() != session) return@runWriteCommandAction
          val end = if (document.lineCount == 0) 0 else document.getLineEndOffset(0)
          document.replaceString(0, end, applyPin(document.getText(TextRange(0, end)), pin))
        })
      }
    }
  }

  companion object {
    fun getInstance(project: Project): IndexPicker = project.service()
  }
}
