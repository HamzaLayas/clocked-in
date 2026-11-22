package com.sterbenden.clockedin.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.sterbenden.clockedin.core.CsvExport
import com.sterbenden.clockedin.core.Reports
import com.sterbenden.clockedin.service.ClockNotifier
import com.sterbenden.clockedin.service.ClockService
import java.nio.file.Files
import java.time.Instant

class ExportCsvAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = exportCsv(e.project)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    companion object {
        private fun suffix(filter: com.sterbenden.clockedin.core.PaidFilter) =
            if (filter == com.sterbenden.clockedin.core.PaidFilter.ALL) "" else "_" + filter.name.lowercase()

        fun exportCsv(project: Project?) {
            val service = ClockService.getInstance()
            val today = Instant.now().atZone(service.zone).toLocalDate()
            val range = DateRangeDialog(project, "Export Sessions to CSV", Reports.weekStart(today), today, askPaid = true)
            if (!range.showAndGet()) return

            val sessions = service.sessions()
            val (from, to) = range.resolve(sessions, service.zone, today)
            val name = if (range.allDates) "clocked-in_all" else "clocked-in_${from}_$to"

            val descriptor = FileSaverDescriptor("Export Sessions to CSV", "Choose where to save the CSV file", "csv")
            val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
                .save("$name${suffix(range.paidFilter)}.csv") ?: return

            val csv = CsvExport.export(sessions, from, to, service.zone, System.currentTimeMillis(), range.paidFilter)
            try {
                Files.writeString(target.file.toPath(), csv)
                ClockNotifier.info(project, "Exported sessions to ${target.file.path}.")
            } catch (ex: Exception) {
                ClockNotifier.warning(project, "Couldn't write ${target.file.path}: ${ex.message}")
            }
        }
    }
}
