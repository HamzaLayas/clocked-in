package com.sterbenden.clockedin.skill

import com.intellij.notification.NotificationAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.sterbenden.clockedin.service.ClockNotifier
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

class InstallSkillAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = install(e.project)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    companion object {
        private const val ALL_PROJECTS = "All Projects"
        private const val THIS_PROJECT = "This Project"
        private const val COPY = "Copy to Clipboard"
        private const val CANCEL = "Cancel"

        fun install(project: Project?) {
            val projectDir = project?.guessProjectDir()?.toNioPathOrNull()
            val options = buildList {
                add(ALL_PROJECTS)
                if (projectDir != null) add(THIS_PROJECT)
                add(COPY)
                add(CANCEL)
            }
            val message = buildString {
                append("Install the clocked-in skill so you can run /clocked-in in an agent session to track your time.\n\n")
                append("All Projects: ${SkillFiles.globalTarget()}\n")
                if (projectDir != null) append("This Project: ${SkillFiles.projectTarget(projectDir)} (can be committed for your team)\n")
                append("Copy to Clipboard: for other agents\n\n")
                append("The agent also needs the IDE's MCP server: Settings | Tools | MCP Server.")
            }
            val choice = Messages.showDialog(
                project, message, "Install Clocked In Agent Skill",
                options.toTypedArray(), 0, Messages.getQuestionIcon(),
            )
            when (options.getOrNull(choice)) {
                ALL_PROJECTS -> writeTo(project, SkillFiles.globalTarget())
                THIS_PROJECT -> writeTo(project, SkillFiles.projectTarget(projectDir!!))
                COPY -> {
                    CopyPasteManager.getInstance().setContents(StringSelection(SkillFiles.bundled))
                    ClockNotifier.info(project, "Copied the clocked-in skill (SKILL.md) to the clipboard.")
                }
                else -> Unit
            }
        }

        /** Writes the bundled skill to [target], asking before replacing a different file. */
        fun writeTo(project: Project?, target: Path, askBeforeReplacing: Boolean = true) {
            when (SkillFiles.stateOf(target)) {
                SkillFiles.State.UP_TO_DATE -> {
                    ClockNotifier.info(project, "The clocked-in skill at $target is already up to date.")
                    return
                }
                SkillFiles.State.OUTDATED, SkillFiles.State.MODIFIED -> if (askBeforeReplacing) {
                    val answer = Messages.showYesNoDialog(
                        project,
                        "$target already exists and differs from the bundled skill. Replace it?",
                        "Replace Agent Skill",
                        Messages.getWarningIcon(),
                    )
                    if (answer != Messages.YES) return
                }
                SkillFiles.State.MISSING -> Unit
            }
            try {
                SkillFiles.write(target)
                LocalFileSystem.getInstance().refreshNioFiles(listOf(target))
                ClockNotifier.create("Installed the clocked-in skill at $target. Run /clocked-in in an agent session to start tracking.")
                    .addAction(NotificationAction.createSimpleExpiring("Copy Path") {
                        CopyPasteManager.getInstance().setContents(StringSelection(target.toString()))
                    })
                    .notify(project)
            } catch (ex: Exception) {
                ClockNotifier.warning(project, "Couldn't write $target: ${ex.message}")
            }
        }
    }
}
