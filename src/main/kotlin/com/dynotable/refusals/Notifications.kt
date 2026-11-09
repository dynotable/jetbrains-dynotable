package com.dynotable.refusals

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil

/** A plugin notification. The balloon renders HTML, so [text] (often the server's) is escaped. */
fun notify(project: Project, text: String, type: NotificationType, actions: List<Pair<String, () -> Unit>> = emptyList()) {
  val notification = NotificationGroupManager.getInstance().getNotificationGroup("DynoTable")
    .createNotification(StringUtil.escapeXmlEntities(text), type)
  actions.forEach { (label, perform) -> notification.addAction(NotificationAction.createSimpleExpiring(label) { perform() }) }
  notification.notify(project)
}
