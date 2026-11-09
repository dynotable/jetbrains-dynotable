package com.dynotable.editor

import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspCodeActionsSupport
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspInlayHintDisabled
import org.eclipse.lsp4j.CodeAction

class DynoTableCustomization : LspCustomization() {
  // A lens click goes through the commands customizer: Run and Open take the same paths as the actions.
  override val commandsCustomizer = DynoTableCommands

  // The plan shows in the lens row instead (PlanCodeVisionProvider).
  override val inlayHintCustomizer = LspInlayHintDisabled

  override val codeActionsCustomizer = object : LspCodeActionsSupport() {
    override fun createQuickFix(lspClient: LspClient, codeAction: CodeAction) = DynoTableQuickFix(lspClient, codeAction)

    override fun createIntentionAction(lspClient: LspClient, codeAction: CodeAction) = DynoTableQuickFix(lspClient, codeAction)
  }
}
