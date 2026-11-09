package com.dynotable.editor

import com.dynotable.connect.ConnectCore
import com.dynotable.files.DynoTableFileType
import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionProvider
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.CodeVisionState
import com.intellij.codeInsight.codeVision.CodeVisionHost
import com.intellij.codeInsight.codeVision.CodeVisionHost.LensInvalidateSignal
import com.intellij.codeInsight.codeVision.ui.model.TextCodeVisionEntry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.util.getLsp4jRange
import com.intellij.platform.lsp.util.getRangeInDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.CodeLensParams
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.TextDocumentIdentifier

const val PLAN_PROVIDER_ID = "DynoTablePlan"
const val LSP_CODE_VISION_PROVIDER_ID = "LspCodeVisionProvider"

/**
 * The server's plan hints, in the lens row. The platform's inlay hints are off: inline, a plan sits
 * after the caret while the user types. The host recomputes this provider on each document change.
 */
class PlanCodeVisionProvider : CodeVisionProvider<Unit>, DumbAware {
  private val lock = Any()

  /** The plans of one document text in one session; `null` until the server answered. */
  private class Fetch(val stamp: Long, val session: Int) {
    lateinit var job: Job

    @Volatile
    var plans: List<PlanLens>? = null
  }

  override fun precomputeOnUiThread(editor: Editor) {}

  /**
   * Returns at once: the host computes all providers in one job and shows their lenses together, so a
   * wait here holds back the server's lenses too. The plans come in the background; their arrival
   * computes this provider again. Never `NotReady`: then the host shows no update from any provider.
   */
  override fun computeCodeVision(editor: Editor, uiData: Unit): CodeVisionState {
    val project = editor.project ?: return CodeVisionState.Ready(emptyList())
    val document = editor.document
    val file = FileDocumentManager.getInstance().getFile(document)?.takeIf { it.fileType is DynoTableFileType }
      ?: return CodeVisionState.Ready(emptyList())
    val core = ConnectCore.getInstance(project)
    if (core.session() == null) return CodeVisionState.Ready(emptyList())
    val session = core.machine.sessions
    val stamp = document.modificationStamp
    val fetch = synchronized(lock) {
      editor.getUserData(FETCH)?.takeIf { it.stamp == stamp && it.session == session }
        ?: Fetch(stamp, session).also { next ->
          editor.getUserData(FETCH)?.job?.cancel()
          next.job = project.service<PlanFetches>().cs.launch {
            next.plans = plans(project, core, file, document)
            ApplicationManager.getApplication().invokeLater({ refresh(project) }, project.disposed)
          }
          editor.putUserData(FETCH, next)
        }
    }
    // Only plans of this text: a plan must never land on a lens of other text.
    val plans = fetch.plans ?: return CodeVisionState.Ready(emptyList())
    return CodeVisionState.Ready(
      ApplicationManager.getApplication().runReadAction<List<Pair<TextRange, CodeVisionEntry>>> {
        plans.mapNotNull { plan -> getRangeInDocument(document, plan.anchor)?.let { it to TextCodeVisionEntry(plan.title, id) } }
      },
    )
  }

  /** Both requests go out together. A failed or unreadable request shows no plan: the plan is a hint only. */
  private suspend fun plans(project: Project, core: ConnectCore, file: VirtualFile, document: Document): List<PlanLens> {
    val client = core.serving() ?: return emptyList()
    if (!core.awaitServerOpened(file)) return emptyList()
    val uri = client.getDocumentIdentifier(file).uri
    val whole = readAction { getLsp4jRange(document, 0, document.textLength) }
    val commands = ServerCommands.getInstance(project)
    return coroutineScope {
      val hints = async { commands.reply(client) { it.textDocumentService.inlayHint(InlayHintParams(TextDocumentIdentifier(uri), whole)) } }
      val lenses = async { commands.reply(client) { it.textDocumentService.codeLens(CodeLensParams(TextDocumentIdentifier(uri))) } }
      val hintAnswer = hints.await() as? Answer ?: return@coroutineScope emptyList()
      val lensAnswer = lenses.await() as? Answer ?: return@coroutineScope emptyList()
      planLenses((lensAnswer.value as? List<*>).orEmpty().filterIsInstance<CodeLens>(), (hintAnswer.value as? List<*>).orEmpty().filterIsInstance<InlayHint>())
    }
  }

  override val defaultAnchor = CodeVisionAnchorKind.Top

  override val relativeOrderings = listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingAfter(LSP_CODE_VISION_PROVIDER_ID))

  override val id = PLAN_PROVIDER_ID

  override val name = "DynoTable query plan"

  override val singleEntryPerLine = false

  companion object {
    private val FETCH = Key.create<Fetch>("DynoTable.planFetch")

    /** The platform recomputes only its own lens provider when a session starts or ends. */
    fun refresh(project: Project) {
      project.service<CodeVisionHost>().invalidateProvider(LensInvalidateSignal(null, listOf(PLAN_PROVIDER_ID)))
    }
  }
}

/** The plan fetches of the project's editors: they end with the project. */
@Service(Service.Level.PROJECT)
class PlanFetches(val cs: CoroutineScope)
