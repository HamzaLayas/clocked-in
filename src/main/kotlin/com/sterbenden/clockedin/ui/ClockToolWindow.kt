package com.sterbenden.clockedin.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.sterbenden.clockedin.core.ClockState
import com.sterbenden.clockedin.core.EditResult
import com.sterbenden.clockedin.core.Formats
import com.sterbenden.clockedin.core.PaidFilter
import com.sterbenden.clockedin.core.Reports
import com.sterbenden.clockedin.core.Session
import com.sterbenden.clockedin.service.ClockListener
import com.sterbenden.clockedin.service.ClockService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.time.Instant
import java.time.LocalDate
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel

class ClockToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ClockPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** Table columns. */
internal object Columns {
    const val SELECT = 0
    const val DATE = 1
    const val START = 2
    const val END = 3
    const val BREAKS = 4
    const val WORKED = 5
    const val STARTED_BY = 6
    const val STOPPED_BY = 7
    const val PAID = 8
    val NAMES = arrayOf("", "Date", "Start", "End", "Breaks", "Worked", "Started by", "Stopped by", "Paid")
}

private class ClockPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val service = ClockService.getInstance()
    private val clockLabel = JBLabel().apply { font = JBUI.Fonts.label(18f).asBold() }
    private val stateLabel = JBLabel()
    private val totalsLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val startButton = JButton("Start", AllIcons.Actions.Execute).apply { addActionListener { ClockUi.start() } }
    private val pauseButton = JButton("Pause", AllIcons.Actions.Pause).apply { addActionListener { ClockUi.pause() } }
    private val stopButton = JButton("Stop", AllIcons.Actions.Suspend).apply { addActionListener { ClockUi.stop() } }
    private val moreButton = JButton(AllIcons.Actions.More).apply {
        toolTipText = "More: print, export, summary, add session, agent skill"
        addActionListener { showMoreMenu(this) }
    }

    // The session list shows the days from..to (inclusive), typed as yyyy-MM-dd.
    private val fromField = dateField(Reports.weekStart(today()))
    private val toField = dateField(today())
    private val rangeTotalLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val allDatesBox = JBCheckBox("All dates").apply {
        toolTipText = "Show every session, ignoring the dates"
        addActionListener {
            fromField.isEnabled = !isSelected
            toField.isEnabled = !isSelected
            refreshTable()
        }
    }
    private val paidFilterBox = ComboBox(PaidFilter.entries.toTypedArray()).apply { addActionListener { refreshTable() } }

    // Ticked rows, by session id. Kept while the list refreshes; dropped once a row is no longer shown.
    private val checked = linkedSetOf<String>()

    private val model = SessionTableModel()
    private val table = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        emptyText.text = "No sessions in this range"
        columnModel.getColumn(Columns.SELECT).apply {
            maxWidth = JBUI.scale(32)
            preferredWidth = JBUI.scale(32)
            headerRenderer = SelectAllHeaderRenderer()
        }
        selectionModel.addListSelectionListener { com.intellij.ide.ActivityTracker.getInstance().inc() }
        // Clicking the checkbox column's header ticks or clears every row.
        tableHeader.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (columnAtPoint(e.point).let { it >= 0 && convertColumnIndexToModel(it) == Columns.SELECT }) {
                    selectAll(!allChecked())
                }
            }
        })
    }

    // The clock ticks every second; the session list only needs an occasional refresh.
    private val clockTimer = Timer(1_000) { refreshHeader() }
    private val tableTimer = Timer(30_000) { refreshTable() }

    init {
        val topRow = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            isOpaque = false
            add(clockLabel, BorderLayout.WEST)
            add(stateLabel, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(startButton)
                add(pauseButton)
                add(stopButton)
                add(moreButton)
            }, BorderLayout.EAST)
        }
        val rangeRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(JBLabel("Sessions from"))
            add(fromField)
            add(JBLabel("to"))
            add(toField)
            add(allDatesBox)
            add(JBLabel("Show"))
            add(paidFilterBox)
        }
        val totalRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(rangeTotalLabel)
        }
        val bottomRow = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(totalsLabel, BorderLayout.NORTH)
            add(rangeRow, BorderLayout.CENTER)
            add(totalRow, BorderLayout.SOUTH)
        }
        val header = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(8, 10)
            add(topRow, BorderLayout.NORTH)
            add(bottomRow, BorderLayout.SOUTH)
        }

        val decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction { ClockUi.addSession(project) }
            .setEditAction { editSelected() }
            .disableRemoveAction()
            .setAddActionName("Add Session")
            .setEditActionName("Edit Session")
            // Our own Delete: the built-in remove button only works on the highlighted row.
            .addExtraAction(DeleteAction())
            .addExtraAction(MarkAction(paid = true))
            .addExtraAction(MarkAction(paid = false))
            .addExtraAction(object : DumbAwareAction("Refresh", null, AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refreshTable()
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            .createPanel()

        add(header, BorderLayout.NORTH)
        add(decorated, BorderLayout.CENTER)

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(ClockListener.TOPIC, ClockListener { refreshHeader(); refreshTable() })
        clockTimer.start()
        tableTimer.start()
        refreshHeader()
        refreshTable()
    }

    override fun dispose() {
        clockTimer.stop()
        tableTimer.stop()
    }

    private fun refreshHeader() {
        val status = service.status()
        clockLabel.text = ClockUi.clockText(status)
        clockLabel.icon = ClockUi.stateIcon(status.state)
        clockLabel.foreground = ClockUi.color(status.state) ?: UIUtil.getLabelForeground()
        stateLabel.text = ClockUi.stateDescription(status)
        totalsLabel.text = "Today ${ClockUi.describe(status.today)}    This week ${ClockUi.describe(status.week)}"
        startButton.text = if (status.state == ClockState.PAUSED) "Resume" else "Start"
        startButton.isEnabled = status.state != ClockState.RUNNING
        pauseButton.isEnabled = status.state == ClockState.RUNNING
        stopButton.isEnabled = status.state != ClockState.STOPPED
    }

    private fun today(): LocalDate = Instant.now().atZone(service.zone).toLocalDate()

    private fun dateField(initial: LocalDate) = JBTextField(initial.toString(), 9).apply {
        toolTipText = "Date as yyyy-MM-dd"
        document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refreshTable()
        })
    }

    /** The typed range, or null (with the bad field outlined in red) when it isn't valid. */
    private fun range(): Pair<LocalDate, LocalDate>? {
        if (allDatesBox.isSelected) {
            fromField.putClientProperty("JComponent.outline", null)
            toField.putClientProperty("JComponent.outline", null)
            return LocalDate.MIN to LocalDate.MAX
        }
        val from = Formats.parseDate(fromField.text)
        val to = Formats.parseDate(toField.text)
        val toValid = to != null && (from == null || !to.isBefore(from))
        fromField.putClientProperty("JComponent.outline", if (from == null) "error" else null)
        toField.putClientProperty("JComponent.outline", if (toValid) null else "error")
        fromField.repaint()
        toField.repaint()
        return if (from != null && to != null && toValid) from to to else null
    }

    private fun refreshTable() {
        val status = service.status()
        val zone = service.zone
        val range = range()
        if (range == null) {
            rangeTotalLabel.text = "Enter dates as yyyy-MM-dd; 'to' can't be before 'from'"
            table.emptyText.text = "Enter a valid date range"
            model.update(emptyList(), status.now)
            updateSelectionControls()
            return
        }
        val all = allDatesBox.isSelected
        val (from, to) = range
        val fromMs = if (all) Long.MIN_VALUE else Reports.startOf(from, zone)
        val toMs = if (all) Long.MAX_VALUE else Reports.startOf(to.plusDays(1), zone)
        val filter = paidFilterBox.selectedItem as PaidFilter
        val inRange = service.sessions().filter { it.start < toMs && it.effectiveEnd(status.now) > fromMs }
        val totals = Reports.totals(inRange, fromMs, toMs, status.now)
        val label = if (all) "All-time total" else "Range total"
        rangeTotalLabel.text = "$label ${ClockUi.describe(totals)} · unpaid ${Formats.duration(totals.unpaidMs)}" +
            " · paid ${Formats.duration(totals.totalMs - totals.unpaidMs)}"
        val where = if (all) "yet" else "in this range"
        table.emptyText.text = if (filter == PaidFilter.ALL) "No sessions $where" else "No ${filter.label.lowercase()} sessions $where"
        val selectedId = selected()?.id
        model.update(inRange.filter(filter::matches).sortedByDescending { it.start }, status.now)
        checked.retainAll(model.rows.map { it.id }.toSet())
        selectedId?.let { id ->
            val row = model.rows.indexOfFirst { it.id == id }
            if (row >= 0) table.selectionModel.setSelectionInterval(row, row)
        }
        updateSelectionControls()
    }

    private fun checkedSessions(): List<Session> = model.rows.filter { it.id in checked }

    /** The ticked rows, or the highlighted row when nothing is ticked. */
    private fun targets(): List<Session> = checkedSessions().ifEmpty { listOfNotNull(selected()) }

    private fun allChecked(): Boolean = model.rows.isNotEmpty() && model.rows.all { it.id in checked }

    internal fun selectAll(select: Boolean) {
        if (select) checked.addAll(model.rows.map { it.id }) else checked.clear()
        model.fireTableDataChanged()
        updateSelectionControls()
    }

    private fun updateSelectionControls() {
        table.tableHeader?.repaint()
        // Re-evaluate the toolbar's Delete / Mark Paid / Mark Unpaid now, not on the next mouse move.
        com.intellij.ide.ActivityTracker.getInstance().inc()
    }

    /** Toolbar action: delete the ticked rows (or the highlighted row). */
    private inner class DeleteAction : DumbAwareAction("Delete", "Delete the ticked sessions", AllIcons.General.Remove) {
        override fun actionPerformed(e: AnActionEvent) = deleteTargets()

        override fun update(e: AnActionEvent) {
            val count = targets().size
            e.presentation.isEnabled = count > 0
            e.presentation.text = if (count > 1) "Delete ($count sessions)" else "Delete"
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /** Toolbar action: mark the ticked rows (or the highlighted row) paid or unpaid. */
    private inner class MarkAction(private val paid: Boolean) : DumbAwareAction(
        if (paid) "Mark Paid" else "Mark Unpaid",
        if (paid) "Mark the ticked sessions as paid" else "Mark the ticked sessions as unpaid",
        if (paid) AllIcons.Actions.Checked else AllIcons.Actions.Close,
    ) {
        override fun actionPerformed(e: AnActionEvent) = mark(paid)

        override fun update(e: AnActionEvent) {
            val targets = targets().filter { it.paid != paid }
            e.presentation.isEnabled = targets.isNotEmpty()
            val base = if (paid) "Mark Paid" else "Mark Unpaid"
            e.presentation.text = if (targets.size > 1) "$base (${targets.size} sessions)" else base
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /** Header for the checkbox column: ticked when every row is ticked; click it to tick or clear all. */
    private inner class SelectAllHeaderRenderer : javax.swing.table.TableCellRenderer {
        private val box = JBCheckBox().apply {
            horizontalAlignment = javax.swing.SwingConstants.CENTER
            isOpaque = false
            toolTipText = "Select all"
        }

        override fun getTableCellRendererComponent(
            table: javax.swing.JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): java.awt.Component {
            box.isSelected = allChecked()
            box.isEnabled = model.rows.isNotEmpty()
            return box
        }
    }

    /** "3 sessions (5h 20m)". */
    private fun describe(sessions: List<Session>): String {
        val now = System.currentTimeMillis()
        val what = if (sessions.size == 1) "1 session" else "${sessions.size} sessions"
        return "$what (${Formats.duration(sessions.sumOf { it.durationMs(now) })})"
    }

    private fun mark(paid: Boolean) {
        val targets = targets().filter { it.paid != paid }
        if (targets.isEmpty()) return
        val state = if (paid) "paid" else "unpaid"
        val answer = Messages.showYesNoDialog(
            project, "Mark ${describe(targets)} as $state?", "Mark Sessions as ${state.replaceFirstChar { it.uppercaseChar() }}",
            Messages.getQuestionIcon(),
        )
        if (answer == Messages.YES) {
            service.setPaid(targets.map { it.id }, paid)
            checked.clear()
        }
    }

    private fun deleteTargets() {
        val targets = targets()
        if (targets.isEmpty()) return
        val answer = Messages.showYesNoDialog(
            project, "Delete ${describe(targets)}? This can't be undone.", "Delete Sessions", Messages.getWarningIcon(),
        )
        if (answer == Messages.YES) {
            service.delete(targets.map { it.id })
            checked.clear()
        }
    }

    private fun showMoreMenu(button: JButton) {
        val group = ActionManager.getInstance().getAction("ClockedIn.More") as? ActionGroup ?: return
        JBPopupFactory.getInstance().createActionGroupPopup(
            null, group, DataManager.getInstance().getDataContext(button),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false,
        ).showUnderneathOf(button)
    }

    private fun selected(): Session? = table.selectedRow.takeIf { it >= 0 }?.let { model.rows[table.convertRowIndexToModel(it)] }

    private fun editSelected() {
        val session = selected() ?: return
        val dialog = SessionTimesDialog(project, "Edit Session", service.zone, session.start, session.end, session.isOpen)
        if (!dialog.showAndGet()) return
        val result = service.edit(session.id, dialog.start, dialog.end)
        if (result is EditResult.Invalid) Messages.showErrorDialog(project, result.reason, "Clocked In")
    }

    private inner class SessionTableModel : AbstractTableModel() {
        var rows: List<Session> = emptyList()
            private set
        private var now = 0L

        fun update(sessions: List<Session>, now: Long) {
            rows = sessions
            this.now = now
            fireTableDataChanged()
        }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = Columns.NAMES.size
        override fun getColumnName(column: Int) = Columns.NAMES[column]

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            Columns.SELECT, Columns.PAID -> Boolean::class.javaObjectType
            else -> String::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex == Columns.SELECT || columnIndex == Columns.PAID

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            val session = rows[rowIndex]
            when (columnIndex) {
                Columns.SELECT -> {
                    if (aValue == true) checked += session.id else checked -= session.id
                    fireTableCellUpdated(rowIndex, columnIndex)
                    updateSelectionControls()
                }
                Columns.PAID -> service.setPaid(listOf(session.id), aValue == true)
            }
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val s = rows[rowIndex]
            val zone = service.zone
            return when (columnIndex) {
                Columns.SELECT -> s.id in checked
                Columns.DATE -> Instant.ofEpochMilli(s.start).atZone(zone).toLocalDate().toString()
                Columns.START -> Formats.time(s.start, zone)
                Columns.END -> s.end?.let { end ->
                    val sameDay = Instant.ofEpochMilli(end).atZone(zone).toLocalDate() ==
                        Instant.ofEpochMilli(s.start).atZone(zone).toLocalDate()
                    if (sameDay) Formats.time(end, zone) else Formats.dateTime(end, zone)
                } ?: if (s.openBreak != null) "on a break" else "running"
                Columns.BREAKS -> s.breakMs(now).let { if (it >= 60_000) Formats.duration(it) else "" }
                Columns.WORKED -> Formats.duration(s.durationMs(now))
                Columns.STARTED_BY -> ClockUi.startedByLabel(s)
                Columns.STOPPED_BY -> ClockUi.stoppedByLabel(s)
                Columns.PAID -> s.paid
                else -> ""
            }
        }
    }
}
