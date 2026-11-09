package com.dynotable.refusals

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil

/** For a server error with no message: the platform refuses a notification with no text. */
const val NO_SENTENCE = "DynoTable could not complete the request."

/** A plugin notification. The balloon renders HTML, so [text] (often the server's) is escaped. */
fun notify(project: Project, text: String, type: NotificationType, actions: List<Pair<String, () -> Unit>> = emptyList()) {
  val notification = NotificationGroupManager.getInstance().getNotificationGroup("DynoTable")
    .createNotification(StringUtil.escapeXmlEntities(text.ifBlank { NO_SENTENCE }), type)
  actions.forEach { (label, perform) -> notification.addAction(NotificationAction.createSimpleExpiring(label) { perform() }) }
  notification.notify(project)
}
