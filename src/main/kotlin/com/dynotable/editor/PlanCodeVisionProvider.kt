package com.dynotable.editor

import com.dynotable.connect.ConnectCore
import com.dynotable.files.DynoTableFileType
import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionInitializer
import com.intellij.codeInsight.codeVision.CodeVisionProvider
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.CodeVisionState
import com.intellij.codeInsight.codeVision.CodeVisionHost.LensInvalidateSignal
import com.intellij.codeInsight.codeVision.ui.model.TextCodeVisionEntry
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.util.getLsp4jRange
import com.intellij.platform.lsp.util.getRangeInDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.eclipse.lsp4j.CodeLensParams
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.TextDocumentIdentifier

const val PLAN_PROVIDER_ID = "DynoTablePlan"
const val LSP_CODE_VISION_PROVIDER_ID = "LspCodeVisionProvider"

/**
 * The server's plan hints, in the lens row. The platform's inlay hints are off: inline, a plan sits
 * after the caret while the user types. The host recomputes this provider on each document change.
 */
class PlanCodeVisionProvider : CodeVisionProvider<Unit>, DumbAware {
  override fun precomputeOnUiThread(editor: Editor) {}

  // Never `NotReady`: then the host shows no update from any provider, the server's lenses included.
  override fun computeCodeVision(editor: Editor, uiData: Unit): CodeVisionState {
    val project = editor.project ?: return CodeVisionState.Ready(emptyList())
    val file = FileDocumentManager.getInstance().getFile(editor.document)?.takeIf { it.fileType is DynoTableFileType }
      ?: return CodeVisionState.Ready(emptyList())
    return CodeVisionState.Ready(runBlockingCancellable { plans(project, file, editor.document) })
  }

  /**
   * Both requests go out together, for the text the server has now. The host drops the answer when
   * the document changes before it shows it, so a plan never lands on a lens of other text.
   * A failed or unreadable request shows no plan: the plan is a hint only.
   */
  private suspend fun plans(project: Project, file: VirtualFile, document: Document): List<Pair<TextRange, CodeVisionEntry>> {
    val core = ConnectCore.getInstance(project)
    val client = core.serving() ?: return emptyList()
    if (!core.awaitServerOpened(file)) return emptyList()
    val uri = client.getDocumentIdentifier(file).uri
    val whole = readAction { getLsp4jRange(document, 0, document.textLength) }
    val plans = try {
      core.machine.trackRun(holdsRestart = false) {
        coroutineScope {
          val hints = async { client.sendRequest { it.textDocumentService.inlayHint(InlayHintParams(TextDocumentIdentifier(uri), whole)) } }
          val lenses = async { client.sendRequest { it.textDocumentService.codeLens(CodeLensParams(TextDocumentIdentifier(uri))) } }
          planLenses(lenses.await().orEmpty(), hints.await().orEmpty())
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      return emptyList()
    }
    return readAction {
      plans.mapNotNull { plan ->
        val range = getRangeInDocument(document, plan.anchor) ?: return@mapNotNull null
        range to TextCodeVisionEntry(plan.title, id)
      }
    }
  }

  override val defaultAnchor = CodeVisionAnchorKind.Top

  override val relativeOrderings = listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingAfter(LSP_CODE_VISION_PROVIDER_ID))

  override val id = PLAN_PROVIDER_ID

  override val name = "DynoTable query plan"

  override val singleEntryPerLine = false

  companion object {
    /** The platform recomputes only its own lens provider when a session starts or ends. */
    fun refresh(project: Project) {
      CodeVisionInitializer.getInstance(project).getCodeVisionHost().invalidateProvider(LensInvalidateSignal(null, listOf(PLAN_PROVIDER_ID)))
    }
  }
}
