package com.dynotable.profiles

import com.dynotable.connect.ConnectCore
import com.dynotable.editor.ServerCommands
import com.dynotable.editor.UNREADABLE
import com.dynotable.protocol.ProfileRow
import com.dynotable.protocol.ProtocolException
import com.dynotable.refusals.notify
import com.dynotable.settings.DynoTableWorkspaceSettings
import com.dynotable.status.Action
import com.dynotable.ui.choose
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SelectProfileAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    ProfilePicker.getInstance(e.project ?: return).launch()
  }
}

/** Lists the profiles of the current session, and connects anew with the chosen one: a session binds its profile at `initialize` only. */
@Service(Service.Level.PROJECT)
class ProfilePicker(private val project: Project, private val cs: CoroutineScope) {
  private val core get() = ConnectCore.getInstance(project)

  /** For a caller on the EDT, which must not wait. */
  fun launch() {
    cs.launch { select() }
  }

  suspend fun select() {
    val client = core.client() ?: return
    val session = core.session() ?: return
    val answer = ServerCommands.getInstance(project).custom(client, LIST_PROFILES) { it.listProfiles() } ?: return
    val rows = try {
      ProfileRow.listFrom(answer.value)
    } catch (_: ProtocolException) {
      notify(project, UNREADABLE, NotificationType.ERROR)
      return
    }
    when (val picker = pickerOf(rows)) {
      is Picker.Empty -> notify(project, picker.sentence, NotificationType.INFORMATION, listOf(Action.OPEN_APP.label to { core.perform(Action.OPEN_APP) }))
      is Picker.Pick -> {
        // A refused row shows its reason and cannot be chosen.
        val item = choose(project, "Select a DynoTable profile", picker.items, { it.text }, { it.detail }) { it.refusal == null } ?: return
        // A replaced session's roster can name a profile of another app.
        if (core.session() != session) return
        DynoTableWorkspaceSettings.getInstance(project).profile = RequestedProfile(item.row.id, item.row.name)
        core.machine.restart()
      }
    }
  }

  companion object {
    private const val LIST_PROFILES = "dynotable/listProfiles"

    fun getInstance(project: Project): ProfilePicker = project.service()
  }
}
