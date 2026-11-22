package com.sterbenden.clockedin.skill

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.openapi.startup.ProjectActivity
import com.sterbenden.clockedin.service.ClockNotifier
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * After a plugin update, offers to refresh installed copies of the skill that have an
 * older version marker. Copies the user changed by hand are left alone.
 */
class SkillUpdateCheck : ProjectActivity {
    override suspend fun execute(project: Project) {
        val targets = buildList {
            if (globalChecked.compareAndSet(false, true)) add(SkillFiles.globalTarget())
            project.guessProjectDir()?.toNioPathOrNull()?.let { add(SkillFiles.projectTarget(it)) }
        }
        for (target in targets) {
            if (SkillFiles.stateOf(target) == SkillFiles.State.OUTDATED) offerUpdate(project, target)
        }
    }

    private fun offerUpdate(project: Project, target: Path) {
        ClockNotifier.create("The clocked-in agent skill at $target is outdated.", NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Update") {
                InstallSkillAction.writeTo(project, target, askBeforeReplacing = false)
            })
            .notify(project)
    }

    private companion object {
        val globalChecked = AtomicBoolean(false)
    }
}
