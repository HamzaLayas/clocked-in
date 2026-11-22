package com.sterbenden.clockedin.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.sterbenden.clockedin.core.Formats
import com.sterbenden.clockedin.core.UnpaidHours
import com.sterbenden.clockedin.core.Xlsx
import com.sterbenden.clockedin.service.ClockNotifier
import com.sterbenden.clockedin.service.ClockService
import java.nio.file.Files
import java.time.Instant

/**
 * Saves every unpaid session as a timesheet workbook to hand in: a summary by week, then a
 * sheet per week with the hours for each day.
 */
class ExportUnpaidAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = exportUnpaid(e.project)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    companion object {
        private const val NAME_KEY = "com.sterbenden.clockedin.timesheetName"

        fun exportUnpaid(project: Project?) {
            val service = ClockService.getInstance()
            val now = System.currentTimeMillis()
            val today = Instant.ofEpochMilli(now).atZone(service.zone).toLocalDate()
            val weeks = UnpaidHours.weeks(service.sessions(), service.zone, now)
            if (weeks.isEmpty()) {
                ClockNotifier.info(project, "There are no unpaid hours to export.")
                return
            }
            val total = Formats.duration(weeks.sumOf { it.seconds } * 1000)

            val properties = PropertiesComponent.getInstance()
            val name = Messages.showInputDialog(
                project,
                "Unpaid hours: $total. Name to show on the timesheet (optional):",
                "Export Unpaid Timesheet",
                null,
                properties.getValue(NAME_KEY, ""),
                null,
            ) ?: return
            properties.setValue(NAME_KEY, name.trim(), "")

            val (from, to) = UnpaidHours.period(weeks)
            val descriptor = FileSaverDescriptor("Export Unpaid Timesheet", "Choose where to save the timesheet", "xlsx")
            val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
                .save("Timesheet_${from}_to_$to.xlsx") ?: return

            try {
                Files.write(target.file.toPath(), Xlsx.write(UnpaidHours.workbook(weeks, today, name.trim())))
                ClockNotifier.info(project, "Saved the timesheet to ${target.file.path}.")
            } catch (ex: Exception) {
                ClockNotifier.warning(project, "Couldn't write ${target.file.path}: ${ex.message}")
            }
        }
    }
}
