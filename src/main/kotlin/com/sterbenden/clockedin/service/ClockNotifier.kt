package com.sterbenden.clockedin.service

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.sterbenden.clockedin.core.ClockEvent
import com.sterbenden.clockedin.core.Formats
import com.sterbenden.clockedin.core.UndoResult

/** Balloon notifications for agent actions, each with Undo. */
object ClockNotifier {
    const val GROUP_ID = "Clocked In"

    fun agentClockedIn(agentName: String?, event: ClockEvent, resumed: Boolean) {
        val time = Formats.time(event.at, ClockService.getInstance().zone)
        val verb = if (resumed) "resumed the clock" else "clocked in"
        notifyWithUndo("${agentLabel(agentName)} $verb at $time.", event)
    }

    fun agentPaused(agentName: String?, event: ClockEvent, workedMs: Long) {
        val time = Formats.time(event.at, ClockService.getInstance().zone)
        notifyWithUndo("${agentLabel(agentName)} paused the clock at $time after ${Formats.duration(workedMs)} of work.", event)
    }

    fun agentClockedOut(agentName: String?, event: ClockEvent, workedMs: Long) {
        val time = Formats.time(event.at, ClockService.getInstance().zone)
        notifyWithUndo("${agentLabel(agentName)} clocked out at $time after ${Formats.duration(workedMs)} of work.", event)
    }

    fun info(project: Project?, content: String) =
        group().createNotification(GROUP_ID, content, NotificationType.INFORMATION).notify(project)

    fun warning(project: Project?, content: String) =
        group().createNotification(GROUP_ID, content, NotificationType.WARNING).notify(project)

    fun create(content: String, type: NotificationType = NotificationType.INFORMATION): Notification =
        group().createNotification(GROUP_ID, content, type)

    fun agentLabel(agentName: String?): String = Formats.agentName(agentName) ?: "An agent"

    private fun notifyWithUndo(content: String, event: ClockEvent) {
        group().createNotification(GROUP_ID, content, NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Undo") {
                when (val result = ClockService.getInstance().undo(event)) {
                    UndoResult.Undone -> Unit
                    is UndoResult.NotPossible -> warning(null, "Couldn't undo: ${result.reason}")
                }
            })
            .notify(null)
    }

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
}
