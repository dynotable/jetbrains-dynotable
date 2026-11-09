package com.dynotable.query

import com.dynotable.explorer.EXPLORER_NODE
import com.dynotable.explorer.Node
import com.dynotable.explorer.queryOf
import com.dynotable.refusals.notify
import com.dynotable.ui.choose
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An OpenSearch query names its index on line 0; Pick Index fills it in. */
private val DIALECTS = listOf(
  Query(Dialect.SQL, ""),
  Query(Dialect.PARTIQL, ""),
  Query(Dialect.OPENSEARCH, "${directiveLine(Pin(""))}\n"),
)

/** On an explorer table or index, a query for it; else a dialect picker. */
class NewQueryAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    NewQuery.getInstance(e.project ?: return).launch(e.getData(EXPLORER_NODE)?.takeIf { it is Node.Table || it is Node.Index })
  }
}

/** Opens a new query in a scratch file. The file open connects, as for every DynoTable file. */
@Service(Service.Level.PROJECT)
class NewQuery(private val project: Project, private val cs: CoroutineScope) {
  /** For a caller on the EDT, which must not wait. */
  fun launch(node: Node?) {
    cs.launch { open(node) }
  }

  suspend fun open(node: Node?) {
    val opening = if (node == null) choose(project, "Select the query language", DIALECTS, { it.dialect.label }) else queryOf(node)
    when (opening) {
      null -> {}
      is Unpinnable -> notify(project, opening.warning, NotificationType.WARNING)
      is Query -> withContext(Dispatchers.EDT) {
        // The platform numbers the name when it is taken (`query_1.partiql`).
        val file = ScratchRootType.getInstance()
          .createScratchFile(project, "query${opening.dialect.suffix}", null, opening.text, ScratchFileService.Option.create_new_always)
          ?: return@withContext
        OpenFileDescriptor(project, file).navigate(true)
      }
    }
  }

  companion object {
    fun getInstance(project: Project): NewQuery = project.service()
  }
}
