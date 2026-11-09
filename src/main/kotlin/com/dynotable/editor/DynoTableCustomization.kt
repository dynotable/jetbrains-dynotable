package com.dynotable.editor

import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspCodeActionsSupport
import com.intellij.platform.lsp.api.customization.LspCustomization
import org.eclipse.lsp4j.CodeAction

class DynoTableCustomization : LspCustomization() {
  override val commandsCustomizer = DynoTableCommands()

  override val codeActionsCustomizer = object : LspCodeActionsSupport() {
    override fun createQuickFix(lspClient: LspClient, codeAction: CodeAction) = DynoTableQuickFix(lspClient, codeAction)

    override fun createIntentionAction(lspClient: LspClient, codeAction: CodeAction) = DynoTableQuickFix(lspClient, codeAction)
  }
}
