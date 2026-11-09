package com.dynotable.connect

import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ShowDocumentParams
import org.eclipse.lsp4j.ShowDocumentResult
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.util.concurrent.CompletableFuture

/**
 * The platform's handler of server-to-client messages, with three changes: it records the diagnostics
 * the server last published per URI, it refuses the requests the server never sends, and it escapes
 * message text, which the platform shows as HTML.
 */
class ServerHandler(
  private val next: LspServerNotificationsHandler,
  private val published: MutableMap<String, List<Diagnostic>>,
) : LspServerNotificationsHandler by next {
  override fun publishDiagnostics(params: PublishDiagnosticsParams) {
    published[params.uri] = params.diagnostics ?: emptyList()
    next.publishDiagnostics(params)
  }

  // A listener on a stale port could send these. The platform would edit files, open links, or
  // answer with the IDE's settings.
  override fun applyEdit(params: ApplyWorkspaceEditParams): CompletableFuture<ApplyWorkspaceEditResponse> = refused()

  override fun showDocument(params: ShowDocumentParams): CompletableFuture<ShowDocumentResult> = refused()

  override fun registerCapability(params: RegistrationParams): CompletableFuture<Void> = refused()

  override fun configuration(params: ConfigurationParams): CompletableFuture<List<Any?>> = refused()

  override fun showMessage(params: MessageParams) = next.showMessage(MessageParams(params.type, escape(params.message)))

  override fun showMessageRequest(params: ShowMessageRequestParams) =
    next.showMessageRequest(ShowMessageRequestParams(params.actions).apply {
      type = params.type
      message = escape(params.message)
    })

  private fun escape(text: String?) = StringUtil.escapeXmlEntities(text.orEmpty())

  private fun <T> refused(): CompletableFuture<T> =
    CompletableFuture.failedFuture(ResponseErrorException(ResponseError(ResponseErrorCode.RequestFailed, "The DynoTable client refuses this request.", null)))
}
