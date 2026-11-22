package com.sterbenden.clockedin.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.PathManager
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.InplaceButton
import com.intellij.util.ui.JBFont
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.sterbenden.clockedin.core.Actor
import com.sterbenden.clockedin.core.ClockState
import com.sterbenden.clockedin.core.EditResult
import com.sterbenden.clockedin.core.Formats
import com.sterbenden.clockedin.core.Reports
import com.sterbenden.clockedin.core.Session
import com.sterbenden.clockedin.core.Timesheet
import com.sterbenden.clockedin.core.Totals
import com.sterbenden.clockedin.service.ClockListener
import com.sterbenden.clockedin.service.ClockNotifier
import com.sterbenden.clockedin.service.ClockService
import com.sterbenden.clockedin.service.ClockStatus
import java.awt.BorderLayout
import java.awt.Color
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.time.Instant
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

/** Shared helpers for the clock's UI. All actions here act as [Actor.ME]. */
object ClockUi {
    const val TOOL_WINDOW_ID = "Clocked In"
    const val SUMMARY_TITLE = "This Week"

    val RUNNING_COLOR: Color = JBColor(0x1E8E3E, 0x5FB865)
    val PAUSED_COLOR: Color = JBColor(0xB86E00, 0xE0A040)

    fun start() {
        ClockService.getInstance().clockIn(Actor.ME)
    }

    fun pause() {
        ClockService.getInstance().pause(Actor.ME)
    }

    fun stop() {
        ClockService.getInstance().clockOut(Actor.ME)
    }

    /** Pauses a running clock; otherwise starts or resumes it. */
    fun toggle() {
        if (ClockService.getInstance().status().state == ClockState.RUNNING) pause() else start()
    }

    /**
     * Worked time in the current session as "01:12:45": it counts while running, stays
     * still on a break, and is back to 00:00:00 once clocked out. The state is shown by
     * [stateIcon] and the text color.
     */
    fun clockText(status: ClockStatus): String = Formats.clock(status.sessionWorkedMs)

    /** The same icons as the Start, Pause and Stop buttons. */
    fun stateIcon(state: ClockState): javax.swing.Icon = when (state) {
        ClockState.RUNNING -> com.intellij.icons.AllIcons.Actions.Execute
        ClockState.PAUSED -> com.intellij.icons.AllIcons.Actions.Pause
        ClockState.STOPPED -> com.intellij.icons.AllIcons.Actions.Suspend
    }

    /** "● 01:12:45" running, "‖ 01:12:45" on a break, "○ 00:00:00" clocked out, for places without buttons. */
    fun clockTextWithState(status: ClockStatus): String = "${symbol(status.state)} ${clockText(status)}"

    fun symbol(state: ClockState): String = when (state) {
        ClockState.RUNNING -> "●"
        ClockState.PAUSED -> "‖"
        ClockState.STOPPED -> "○"
    }

    fun color(state: ClockState): Color? = when (state) {
        ClockState.RUNNING -> RUNNING_COLOR
        ClockState.PAUSED -> PAUSED_COLOR
        ClockState.STOPPED -> null
    }

    /** One line describing the current state. */
    fun stateDescription(status: ClockStatus): String {
        val zone = ClockService.getInstance().zone
        return when (status.state) {
            ClockState.RUNNING -> {
                val s = status.running!!
                "Clocked in since ${Formats.time(s.start, zone)} by ${startedByLabel(s)}" + breaksNote(s, status.now)
            }
            ClockState.PAUSED -> {
                val p = status.pause!!
                val by = when (p.by) {
                    Actor.ME -> "you"
                    Actor.AGENT -> Formats.agentName(p.agent) ?: "an agent"
                    Actor.SYSTEM -> "the IDE"
                }
                "On a break since ${Formats.time(p.start, zone)} (paused by $by) · break ${Formats.clock(status.now - p.start)}"
            }
            ClockState.STOPPED -> "Clocked out"
        }
    }

    private fun breaksNote(s: Session, now: Long): String {
        val ms = s.breakMs(now)
        return if (ms >= 1_000) " · breaks ${Formats.duration(ms)}" else ""
    }

    fun tooltip(status: ClockStatus): String {
        val hint = when (status.state) {
            ClockState.RUNNING -> "Pause or stop with the buttons next to it"
            ClockState.PAUSED -> "Resume with ▶"
            ClockState.STOPPED -> "Start with ▶"
        }
        return "${stateDescription(status)}\nThis session ${Formats.clock(status.sessionWorkedMs)} worked" +
            "\nToday ${describe(status.today)} · this week ${describe(status.week)}\n$hint"
    }

    /** "01:30:00 (you 01:00:00, agents 00:30:00)". The parts always add up to the total. */
    fun describe(t: Totals): String {
        if (t.totalMs < 1_000) return Formats.duration(t.totalMs)
        val agentMs = Formats.seconds(t.agentStartedMs) * 1000
        val youMs = Formats.seconds(t.totalMs) * 1000 - agentMs
        return "${Formats.duration(t.totalMs)} (you ${Formats.duration(youMs)}, agents ${Formats.duration(agentMs)})"
    }

    fun startedByLabel(s: Session): String = when (s.startedBy) {
        Actor.ME -> "You"
        Actor.AGENT -> Formats.agentName(s.startedByAgent) ?: "Agent"
        Actor.SYSTEM -> "IDE"
    }

    fun stoppedByLabel(s: Session): String = when (s.stoppedBy) {
        null -> ""
        Actor.ME -> "You"
        Actor.AGENT -> Formats.agentName(s.stoppedByAgent) ?: "Agent"
        Actor.SYSTEM -> "IDE"
    }

    fun agentName(raw: String?): String? = Formats.agentName(raw)

    fun openToolWindow(project: Project) {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
    }

    fun addSession(project: Project?) {
        val service = ClockService.getInstance()
        val now = System.currentTimeMillis()
        val dialog = SessionTimesDialog(project, "Add Session", service.zone, now - 60 * 60_000, now, running = false)
        if (!dialog.showAndGet()) return
        val result = service.add(dialog.start, dialog.end!!)
        if (result is EditResult.Invalid) Messages.showErrorDialog(project, result.reason, "Clocked In")
    }

    /** A small popup with the current state, today's and this week's totals, and recent sessions. */
    fun showSummary(project: Project?, anchor: JComponent?) {
        var popup: JBPopup? = null
        val content = summaryPanel(ClockService.getInstance()) { popup?.cancel() }
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .setRequestFocus(true)
            .setResizable(false)
            .setMovable(false)
            .createPopup()
        when {
            anchor != null && anchor.isShowing -> popup.showUnderneathOf(anchor)
            project != null -> popup.showCenteredInCurrentWindow(project)
            else -> popup.showInFocusCenter()
        }
    }

    /** The summary's content: a header with the title and a close button, then the totals. */
    internal fun summaryPanel(service: ClockService, onClose: () -> Unit): JComponent {
        val status = service.status()
        val zone = service.zone
        val recent = service.sessions().sortedByDescending { it.start }.take(5)
        val background = JBUI.CurrentTheme.Popup.BACKGROUND
        val secondary = UIUtil.getContextHelpForeground()

        val root = JPanel(BorderLayout()).apply {
            this.background = background
            border = JBUI.Borders.empty(6, 12, 10, 8)
        }

        val close = InplaceButton(IconButton("Close", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { onClose() }
            .apply { toolTipText = "Close"; accessibleContext.accessibleName = "Close" }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(6)
            add(JBLabel(SUMMARY_TITLE).apply { font = JBFont.label().asBold() }, BorderLayout.WEST)
            add(close, BorderLayout.EAST)
        }
        root.add(header, BorderLayout.NORTH)

        val body = JPanel(GridBagLayout()).apply { isOpaque = false; border = JBUI.Borders.emptyRight(4) }
        val c = GridBagConstraints().apply { anchor = GridBagConstraints.WEST; gridy = 0 }
        fun cell(text: String, x: Int, color: Color? = null, bold: Boolean = false, span: Int = 1, icon: javax.swing.Icon? = null) {
            c.gridx = x
            c.gridwidth = span
            c.insets = JBUI.insets(2, 0, 2, 16)
            body.add(JBLabel(text).apply {
                this.icon = icon
                if (color != null) foreground = color
                if (bold) font = JBFont.label().asBold()
            }, c)
        }

        cell(stateDescription(status), 0, color(status.state), span = 4, icon = stateIcon(status.state))
        c.gridy++
        c.insets = JBUI.insets(6, 0, 0, 0)
        body.add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)), c)
        c.gridy++
        cell("Today", 0, secondary)
        cell(describe(status.today), 1, span = 3)
        c.gridy++
        cell("This week", 0, secondary)
        cell(describe(status.week), 1, span = 3)
        c.gridy++

        if (recent.isNotEmpty()) {
            body.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)), c.apply { gridx = 0; gridwidth = 4 })
            c.gridy++
            cell("Recent sessions", 0, bold = true, span = 4)
            c.gridy++
            for (s in recent) {
                val end = s.end?.let { Formats.time(it, zone) } ?: if (s.openBreak != null) "on a break" else "running"
                cell("${Formats.dateTime(s.start, zone)} – $end", 0, span = 2)
                cell(Formats.duration(s.durationMs(status.now)), 2)
                cell(startedByLabel(s), 3, secondary)
                c.gridy++
            }
        }
        root.add(body, BorderLayout.CENTER)
        return root
    }

    /**
     * Asks for a date range, then opens the timesheet in the web browser with its print
     * dialog. Browsers can print to any printer and always offer "Save as PDF", even when
     * no printer is set up.
     */
    fun printTimesheet(project: Project?) {
        val service = ClockService.getInstance()
        val today = Instant.now().atZone(service.zone).toLocalDate()
        val range = DateRangeDialog(project, "Print Timesheet", Reports.weekStart(today), today)
        if (!range.showAndGet()) return
        val sessions = service.sessions()
        val (from, to) = range.resolve(sessions, service.zone, today)
        val html = Timesheet.html(sessions, from, to, service.zone, System.currentTimeMillis(), autoPrint = true)
        try {
            val dir = PathManager.getSystemDir().resolve("clocked-in").resolve("timesheets")
            Files.createDirectories(dir)
            val file = dir.resolve("timesheet_${from}_$to.html")
            Files.writeString(file, html)
            BrowserUtil.browse(file)
        } catch (e: Exception) {
            ClockNotifier.warning(project, "Couldn't open the timesheet: ${e.message}")
        }
    }

}

abstract class ClockAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    protected fun state(): ClockState = ClockService.getInstance().status().state
}

/** Start, or resume after a break. */
class ClockInAction : ClockAction() {
    override fun actionPerformed(e: AnActionEvent) = ClockUi.start()

    override fun update(e: AnActionEvent) {
        val state = state()
        e.presentation.isEnabled = state != ClockState.RUNNING
        e.presentation.text = if (state == ClockState.PAUSED) "Resume" else "Start"
    }
}

class PauseAction : ClockAction() {
    override fun actionPerformed(e: AnActionEvent) = ClockUi.pause()

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = state() == ClockState.RUNNING
    }
}

/** Clock out, from a running clock or a break. */
class ClockOutAction : ClockAction() {
    override fun actionPerformed(e: AnActionEvent) = ClockUi.stop()

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = state() != ClockState.STOPPED
    }
}

class ToggleClockAction : ClockAction() {
    override fun actionPerformed(e: AnActionEvent) = ClockUi.toggle()

    override fun update(e: AnActionEvent) {
        e.presentation.text = when (state()) {
            ClockState.RUNNING -> "Pause"
            ClockState.PAUSED -> "Resume"
            ClockState.STOPPED -> "Start"
        }
    }
}

class ShowSummaryAction : ClockAction() {
    override fun actionPerformed(e: AnActionEvent) = ClockUi.showSummary(e.project, null)
}

class PrintTimesheetAction : ClockAction() {
    override fun actionPerformed(e: AnActionEvent) = ClockUi.printTimesheet(e.project)
}

class AddSessionAction : ClockAction() {
    override fun actionPerformed(e: AnActionEvent) = ClockUi.addSession(e.project)
}

class OpenToolWindowAction : ClockAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(ClockUi::openToolWindow)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}

/**
 * The time display in the main toolbar: "● 03:12:45" (today's total), updated every
 * second while the clock runs. Click it for a summary.
 */
class ToolbarClockAction : AnAction(), CustomComponentAction, DumbAware {
    init {
        // Toolbars only refresh their buttons on user activity; nudge them when the clock
        // changes so Start, Pause and Stop are enabled correctly right away.
        ApplicationManager.getApplication().messageBus.connect()
            .subscribe(ClockListener.TOPIC, ClockListener { ActivityTracker.getInstance().inc() })
    }

    override fun actionPerformed(e: AnActionEvent) = ClockUi.showSummary(e.project, null)

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent = ClockLabel()

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
        (component as? ClockLabel)?.refresh()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

/** A label showing today's total in the state's color, refreshing itself every second while visible. */
class ClockLabel : JBLabel() {
    private val timer = Timer(1_000) { refresh() }

    init {
        border = JBUI.Borders.empty(0, 6)
        iconTextGap = JBUI.scale(4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = JBUI.Fonts.label().asBold()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val project = DataManager.getInstance().getDataContext(this@ClockLabel).getData(CommonDataKeys.PROJECT)
                ClockUi.showSummary(project, this@ClockLabel)
            }
        })
        refresh()
    }

    fun refresh() {
        val status = ClockService.getInstance().status()
        text = ClockUi.clockText(status)
        icon = ClockUi.stateIcon(status.state)
        foreground = ClockUi.color(status.state) ?: UIUtil.getLabelForeground()
        toolTipText = "<html>" + ClockUi.tooltip(status).replace("\n", "<br>") + "<br>Click for a summary</html>"
    }

    override fun addNotify() {
        super.addNotify()
        refresh()
        timer.start()
    }

    override fun removeNotify() {
        timer.stop()
        super.removeNotify()
    }
}
