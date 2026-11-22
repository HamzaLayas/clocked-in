package com.sterbenden.clockedin

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.sterbenden.clockedin.core.Actor
import com.sterbenden.clockedin.core.ClockInResult
import com.sterbenden.clockedin.core.PauseResult
import com.sterbenden.clockedin.service.ClockNotifier
import com.sterbenden.clockedin.service.ClockService
import com.sterbenden.clockedin.ui.ClockLabel
import com.sterbenden.clockedin.ui.ClockStatusBarWidget
import com.sterbenden.clockedin.ui.ClockToolWindowFactory
import com.sterbenden.clockedin.ui.ClockUi
import com.sterbenden.clockedin.ui.Columns
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTable

/**
 * Renders the tool window off-screen to `build/ui-snapshots/` and checks what it shows,
 * plus the toolbar clock label and the agent notifications with Undo.
 */
class UiSnapshotTest : BasePlatformTestCase() {
    private val hour = 3_600_000L

    override fun setUp() {
        super.setUp()
        clear()
    }

    override fun tearDown() {
        try {
            clear()
        } finally {
            super.tearDown()
        }
    }

    private fun clear() {
        val clock = ClockService.getInstance()
        clock.clockOut(Actor.ME)
        clock.sessions().forEach { clock.delete(it.id) }
    }

    fun `test tool window follows running, paused and stopped`() {
        val clock = ClockService.getInstance()
        val now = System.currentTimeMillis()
        clock.add(now - 3 * hour, now - 2 * hour)                    // I worked an hour
        clock.clockIn(Actor.AGENT, "claude-code")                    // an agent is working now
        clock.edit(clock.status().running!!.id, now - 30 * 60_000, null)

        val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        ClockToolWindowFactory().createToolWindowContent(project, toolWindow)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val panel = toolWindow.contentManager.getContent(0)!!.component
        val table = allOf<JTable>(panel).single()

        // The clock shows this session's worked time, not today's total.
        var labels = allOf<JLabel>(panel).map { it.text.orEmpty() }
        assertTrue(labels.toString(), labels.any { it.startsWith("00:30:0") })
        assertTrue(labels.toString(), labels.any { it.startsWith("Clocked in since") && it.contains("by Claude Code") })
        assertTrue(labels.toString(), labels.any { it.startsWith("Today 01:30:0") && it.contains("(you 01:00:00, agents 00:30:0") })
        assertEquals(mapOf("Start" to false, "Pause" to true, "Stop" to true), buttons(panel))
        assertEquals(2, table.rowCount)
        assertEquals("running", table.getValueAt(0, Columns.END))
        assertEquals("Claude Code", table.getValueAt(0, Columns.STARTED_BY))
        snapshot(panel, "tool-window-running.png")

        // A break keeps the same session; the clock stays where it was.
        clock.pause(Actor.ME)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        labels = allOf<JLabel>(panel).map { it.text.orEmpty() }
        assertTrue(labels.toString(), labels.any { it.startsWith("00:30:0") })
        assertTrue(labels.toString(), labels.any { it.startsWith("On a break since") && it.contains("paused by you") })
        assertEquals(mapOf("Resume" to true, "Pause" to false, "Stop" to true), buttons(panel))
        assertEquals(2, table.rowCount)
        assertEquals("on a break", table.getValueAt(0, Columns.END))
        snapshot(panel, "tool-window-paused.png")

        // Stopping ends the session and the clock goes back to zero.
        clock.clockOut(Actor.ME)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        labels = allOf<JLabel>(panel).map { it.text.orEmpty() }
        assertTrue(labels.toString(), labels.contains("Clocked out"))
        assertTrue(labels.toString(), labels.contains("00:00:00"))
        assertEquals(mapOf("Start" to true, "Pause" to false, "Stop" to false), buttons(panel))
        snapshot(panel, "tool-window-stopped.png")

        val widget = ClockStatusBarWidget()
        try {
            assertEquals("○ 00:00:00", widget.getText())
        } finally {
            widget.dispose()
        }
    }

    fun `test session list follows the typed date range`() {
        val clock = ClockService.getInstance()
        val now = System.currentTimeMillis()
        val zone = clock.zone
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        clock.add(now - 3 * hour, now - 2 * hour)                                   // today
        val longAgo = today.minusDays(40).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        clock.add(longAgo, longAgo + hour)                                          // 40 days ago

        val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        ClockToolWindowFactory().createToolWindowContent(project, toolWindow)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val panel = toolWindow.contentManager.getContent(0)!!.component
        val (from, to) = allOf<com.intellij.ui.components.JBTextField>(panel)
        val table = allOf<JTable>(panel).single()

        // Default: Monday of this week to today.
        assertEquals(com.sterbenden.clockedin.core.Reports.weekStart(today).toString(), from.text)
        assertEquals(today.toString(), to.text)
        assertEquals(1, table.rowCount)
        assertTrue(rangeText(panel), rangeText(panel).startsWith("Range total 01:00:00"))

        from.text = today.minusDays(60).toString()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(2, table.rowCount)
        assertTrue(rangeText(panel), rangeText(panel).startsWith("Range total 02:00:00"))

        to.text = today.minusDays(30).toString()
        assertEquals(1, table.rowCount)

        to.text = "not a date"
        assertEquals(0, table.rowCount)
        assertEquals("error", to.getClientProperty("JComponent.outline"))
        assertTrue(rangeText(panel), rangeText(panel).startsWith("Enter dates"))

        to.text = today.minusDays(90).toString()                                    // before 'from'
        assertEquals(0, table.rowCount)
        assertEquals("error", to.getClientProperty("JComponent.outline"))

        to.text = today.toString()
        assertEquals(2, table.rowCount)
        assertNull(to.getClientProperty("JComponent.outline"))
        snapshot(panel, "tool-window-range.png")

        // "All dates" ignores the fields, even a narrow or broken range.
        from.text = today.toString()
        to.text = "broken"
        assertEquals(0, table.rowCount)
        val all = allOf<com.intellij.ui.components.JBCheckBox>(panel).single { it.text == "All dates" }
        all.doClick()
        assertFalse(from.isEnabled)
        assertFalse(to.isEnabled)
        assertEquals(2, table.rowCount)
        assertTrue(rangeText(panel), rangeText(panel).startsWith("All-time total 02:00:00"))
        snapshot(panel, "tool-window-all-dates.png")
        all.doClick()
        assertTrue(from.isEnabled)
        assertEquals(0, table.rowCount)
    }

    fun `test paid checkbox, filter, and table toolbar actions on ticked rows`() {
        val clock = ClockService.getInstance()
        val now = System.currentTimeMillis()
        clock.add(now - 5 * hour, now - 4 * hour)
        clock.add(now - 3 * hour, now - 2 * hour)

        val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        ClockToolWindowFactory().createToolWindowContent(project, toolWindow)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val panel = toolWindow.contentManager.getContent(0)!!.component
        val table = allOf<JTable>(panel).single()
        val filter = allOf<com.intellij.openapi.ui.ComboBox<*>>(panel).single()

        // The old button row is gone; the actions live on the table's toolbar.
        assertTrue(allOf<AbstractButton>(panel).none { it.text.orEmpty().startsWith("Mark") || it.text == "Delete" })
        assertTrue(allOf<JLabel>(panel).none { it.text == "Select all" })
        val markPaid = toolbarAction(panel, "Mark Paid")
        val markUnpaid = toolbarAction(panel, "Mark Unpaid")
        val delete = toolbarAction(panel, "Delete")
        // Only our Delete: the table's built-in remove button (which needs a highlighted row) is not used.
        assertTrue(allOf<Component>(panel).filterIsInstance<com.intellij.openapi.actionSystem.ActionToolbar>()
            .flatMap { it.actionGroup.getChildren(null).toList() }
            .none { it.templateText == "Remove" })

        assertEquals("Paid", table.getColumnName(Columns.PAID))
        assertTrue(rangeText(panel), rangeText(panel).contains("unpaid 02:00:00 · paid 00:00:00"))

        // Paid checkbox on one row.
        table.setValueAt(true, 0, Columns.PAID)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(true, table.getValueAt(0, Columns.PAID))
        assertTrue(rangeText(panel), rangeText(panel).contains("unpaid 01:00:00 · paid 01:00:00"))
        filter.selectedItem = com.sterbenden.clockedin.core.PaidFilter.UNPAID
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, table.rowCount)
        filter.selectedItem = com.sterbenden.clockedin.core.PaidFilter.ALL
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        // Nothing ticked or highlighted: the actions are off.
        table.clearSelection()
        assertFalse(enabled(markPaid))
        assertFalse(enabled(delete))

        // Tick the unpaid row and mark it paid.
        table.setValueAt(true, 1, Columns.SELECT)
        assertTrue(enabled(markPaid))
        assertFalse(enabled(markUnpaid))
        withYes { perform(markPaid) }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(clock.sessions().all { it.paid })

        // Click the header checkbox to tick every row, then mark unpaid and delete.
        clickSelectAllHeader(table)
        assertEquals(true, table.getValueAt(0, Columns.SELECT))
        assertEquals(true, table.getValueAt(1, Columns.SELECT))
        assertEquals("Mark Unpaid (2 sessions)", presentation(markUnpaid).text)
        withYes { perform(markUnpaid) }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(clock.sessions().none { it.paid })
        // Tick via the header only, with no highlighted row: Delete must still be available.
        table.clearSelection()
        clickSelectAllHeader(table)
        assertTrue(table.selectedRowCount == 0)
        assertTrue(enabled(delete))
        assertEquals("Delete (2 sessions)", presentation(delete).text)
        snapshot(panel, "tool-window-selected.png")
        withYes { perform(delete) }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(clock.sessions().isEmpty())
        assertEquals(0, table.rowCount)
    }

    private fun clickSelectAllHeader(table: JTable) {
        val header = table.tableHeader
        val click = java.awt.event.MouseEvent(header, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false)
        header.mouseListeners.forEach { it.mouseClicked(click) }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun toolbarAction(panel: Component, text: String): com.intellij.openapi.actionSystem.AnAction =
        allOf<Component>(panel).filterIsInstance<com.intellij.openapi.actionSystem.ActionToolbar>()
            .flatMap { it.actionGroup.getChildren(null).toList() }
            .single { it.templateText == text }

    private fun presentation(action: com.intellij.openapi.actionSystem.AnAction): com.intellij.openapi.actionSystem.Presentation {
        val presentation = action.templatePresentation.clone()
        val event = AnActionEvent.createEvent(
            SimpleDataContext.getProjectContext(project), presentation, "test", ActionUiKind.TOOLBAR, null,
        )
        action.update(event)
        return presentation
    }

    private fun enabled(action: com.intellij.openapi.actionSystem.AnAction) = presentation(action).isEnabled

    private fun perform(action: com.intellij.openapi.actionSystem.AnAction) {
        val event = AnActionEvent.createEvent(
            SimpleDataContext.getProjectContext(project), action.templatePresentation.clone(), "test", ActionUiKind.TOOLBAR, null,
        )
        action.actionPerformed(event)
    }

    private fun withYes(block: () -> Unit) {
        com.intellij.openapi.ui.TestDialogManager.setTestDialog(com.intellij.openapi.ui.TestDialog.YES)
        try {
            block()
        } finally {
            com.intellij.openapi.ui.TestDialogManager.setTestDialog(com.intellij.openapi.ui.TestDialog.DEFAULT)
        }
    }

    fun `test toolbar clock label shows hh mm ss and the state`() {
        val label = ClockLabel()
        assertEquals("00:00:00", label.text)
        assertSame(com.intellij.icons.AllIcons.Actions.Suspend, label.icon)
        ClockService.getInstance().clockIn(Actor.ME)
        label.refresh()
        assertEquals("00:00:00", label.text)
        assertSame(com.intellij.icons.AllIcons.Actions.Execute, label.icon)
        assertEquals(ClockUi.RUNNING_COLOR, label.foreground)
        ClockService.getInstance().pause(Actor.ME)
        label.refresh()
        assertEquals("00:00:00", label.text)
        assertSame(com.intellij.icons.AllIcons.Actions.Pause, label.icon)
        assertEquals(ClockUi.PAUSED_COLOR, label.foreground)
        assertTrue(label.toolTipText, label.toolTipText.contains("On a break"))
        snapshot(label, "toolbar-clock-paused.png", label.preferredSize.width + 4, label.preferredSize.height + 4)
    }

    fun `test summary shows totals and its close button closes it`() {
        val service = ClockService.getInstance()
        service.clockIn(Actor.ME)
        service.pause(Actor.ME)
        var closed = 0
        val panel = ClockUi.summaryPanel(service) { closed++ }
        val texts = allOf<JLabel>(panel).map { it.text }
        assertTrue(texts.toString(), ClockUi.SUMMARY_TITLE in texts)
        assertTrue(texts.toString(), "Today" in texts && "This week" in texts && "Recent sessions" in texts)
        assertTrue(texts.toString(), texts.any { it.endsWith("on a break") })
        allOf<com.intellij.ui.InplaceButton>(panel).single().doClick()
        assertEquals(1, closed)
        val size = panel.preferredSize
        snapshot(panel, "summary-paused.png", size.width, size.height)
    }

    fun `test totals split always adds up`() {
        val clock = ClockService.getInstance()
        val now = System.currentTimeMillis()
        clock.add(now - 600_000, now - 498_000)   // 1.7 minutes, started by me
        clock.clockIn(Actor.AGENT, "claude-code")
        clock.edit(clock.status().running!!.id, now - 168_000, null)   // about 2.8 minutes, agent
        val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        ClockToolWindowFactory().createToolWindowContent(project, toolWindow)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val totals = allOf<JLabel>(toolWindow.contentManager.getContent(0)!!.component)
            .first { it.text.orEmpty().startsWith("Today") }.text
        // 1.7 + 2.8 minutes, to the second, and the parts add up.
        assertTrue(totals, totals.startsWith("Today 00:04:3") && totals.contains("(you 00:01:42, agents 00:02:4"))
    }

    fun `test agent notifications offer Undo`() {
        val shown = mutableListOf<Notification>()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(Notifications.TOPIC, object : Notifications {
                override fun notify(notification: Notification) {
                    shown += notification
                }
            })
        val clock = ClockService.getInstance()

        val started = clock.clockIn(Actor.AGENT, "claude-code") as ClockInResult.Started
        ClockNotifier.agentClockedIn("claude-code", started.event, started.resumed)
        val paused = clock.pause(Actor.AGENT, "claude-code") as PauseResult.Paused
        ClockNotifier.agentPaused("claude-code", paused.event, 0)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val ours = shown.filter { it.groupId == ClockNotifier.GROUP_ID }
        assertTrue(ours[0].content, ours[0].content.startsWith("Claude Code clocked in at "))
        assertTrue(ours[1].content, ours[1].content.startsWith("Claude Code paused the clock at "))

        val event = AnActionEvent.createEvent(SimpleDataContext.getProjectContext(project), null, "test", ActionUiKind.NONE, null)
        (ours[1].actions.single { it.templateText == "Undo" } as NotificationAction).actionPerformed(event, ours[1])
        assertNotNull("undoing the pause resumes the session", clock.status().running)
        (ours[0].actions.single { it.templateText == "Undo" } as NotificationAction).actionPerformed(event, ours[0])
        assertTrue(clock.sessions().isEmpty())
    }

    private fun rangeText(panel: Component): String = allOf<JLabel>(panel).first {
        val t = it.text.orEmpty()
        t.startsWith("Range total") || t.startsWith("All-time total") || t.startsWith("Enter")
    }.text

    private fun button(panel: Component, text: String): AbstractButton = allOf<AbstractButton>(panel).single { it.text == text }

    private fun buttons(root: Component): Map<String, Boolean> =
        allOf<AbstractButton>(root).filter { it.text in setOf("Start", "Resume", "Pause", "Stop") }
            .associate { it.text to it.isEnabled }

    private inline fun <reified T : Component> allOf(root: Component): List<T> = descendants(root).filterIsInstance<T>()

    private fun descendants(root: Component): List<Component> =
        listOf(root) + ((root as? Container)?.components?.flatMap { descendants(it) } ?: emptyList())

    private fun snapshot(component: JComponent, name: String, width: Int = 1000, height: Int = 320) {
        component.setSize(width, height)
        layoutAll(component)
        val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, width, height)
            component.paint(g)
        } finally {
            g.dispose()
        }
        val dir = Path.of(System.getProperty("user.dir"), "build", "ui-snapshots")
        Files.createDirectories(dir)
        ImageIO.write(image, "png", dir.resolve(name).toFile())
    }

    private fun layoutAll(c: Component) {
        c.doLayout()
        if (c is Container) c.components.forEach(::layoutAll)
    }
}
