package com.sterbenden.clockedin.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.sterbenden.clockedin.core.UnpaidHours
import com.sterbenden.clockedin.core.Xlsx
import com.sterbenden.clockedin.service.ClockNotifier
import com.sterbenden.clockedin.service.ClockService
import java.nio.file.Files
import java.time.Instant

/** Saves every unpaid session as an Excel workbook: a summary by week, then a sheet per week with its days. */
class ExportUnpaidAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = exportUnpaid(e.project)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    companion object {
        fun exportUnpaid(project: Project?) {
            val service = ClockService.getInstance()
            val now = System.currentTimeMillis()
            val today = Instant.ofEpochMilli(now).atZone(service.zone).toLocalDate()
            val weeks = UnpaidHours.weeks(service.sessions(), service.zone, now)
            if (weeks.isEmpty()) {
                ClockNotifier.info(project, "There are no unpaid hours to export.")
                return
            }

            val descriptor = FileSaverDescriptor("Export Unpaid Hours", "Choose where to save the spreadsheet", "xlsx")
            val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
                .save("unpaid-hours_$today.xlsx") ?: return

            try {
                Files.write(target.file.toPath(), Xlsx.write(UnpaidHours.workbook(weeks, today)))
                ClockNotifier.info(project, "Exported unpaid hours to ${target.file.path}.")
            } catch (ex: Exception) {
                ClockNotifier.warning(project, "Couldn't write ${target.file.path}: ${ex.message}")
            }
        }
    }
}
