package com.sterbenden.clockedin

import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sterbenden.clockedin.core.Actor
import com.sterbenden.clockedin.service.ClockService
import com.sterbenden.clockedin.skill.InstallSkillAction
import com.sterbenden.clockedin.skill.SkillFiles
import com.sterbenden.clockedin.ui.ClockStatusBarWidget
import com.sterbenden.clockedin.ui.ClockToolWindowFactory
import java.nio.file.Files

class PluginWiringTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        val clock = ClockService.getInstance()
        clock.clockOut(Actor.ME)
        clock.sessions().forEach { clock.delete(it.id) }
    }

    override fun tearDown() {
        try {
            val clock = ClockService.getInstance()
            clock.clockOut(Actor.ME)
            clock.sessions().forEach { clock.delete(it.id) }
        } finally {
            super.tearDown()
        }
    }

    fun `test actions are registered with the toggle shortcut`() {
        val manager = ActionManager.getInstance()
        for (id in listOf(
            "ClockedIn.Toggle", "ClockedIn.ClockIn", "ClockedIn.Pause", "ClockedIn.ClockOut", "ClockedIn.Summary",
            "ClockedIn.PrintTimesheet", "ClockedIn.ExportUnpaid", "ClockedIn.OpenToolWindow", "ClockedIn.AddSession",
            "ClockedIn.InstallSkill",
        )) {
            assertNotNull(id, manager.getAction(id))
        }
        val shortcuts = KeymapManager.getInstance().activeKeymap.getShortcuts("ClockedIn.Toggle")
        assertTrue(shortcuts.isNotEmpty())
    }

    fun `test toolbar has the clock, start, pause, stop and more menu next to the run controls`() {
        val manager = ActionManager.getInstance()
        val run = manager.getAction("RunToolbarMainActionGroup") as com.intellij.openapi.actionSystem.DefaultActionGroup
        assertEquals("ClockedIn.Toolbar", manager.getId(run.getChildActionsOrStubs().first()))
        val ours = manager.getAction("ClockedIn.Toolbar") as com.intellij.openapi.actionSystem.DefaultActionGroup
        val ids = ours.getChildActionsOrStubs().mapNotNull { manager.getId(it) }
        assertEquals(listOf("ClockedIn.ToolbarClock", "ClockedIn.ClockIn", "ClockedIn.Pause", "ClockedIn.ClockOut", "ClockedIn.ToolbarMore"), ids)
        val more = manager.getAction("ClockedIn.More") as com.intellij.openapi.actionSystem.DefaultActionGroup
        val moreIds = more.getChildActionsOrStubs().mapNotNull { manager.getId(it) }
        assertEquals(
            listOf("ClockedIn.Summary", "ClockedIn.PrintTimesheet", "ClockedIn.ExportUnpaid", "ClockedIn.OpenToolWindow", "ClockedIn.AddSession", "ClockedIn.InstallSkill"),
            moreIds,
        )
    }

    fun `test start pause stop buttons enable by state`() {
        val manager = ActionManager.getInstance()
        fun enabled(id: String): Pair<Boolean, String> {
            val presentation = com.intellij.openapi.actionSystem.Presentation()
            val event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
                com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project), presentation,
                com.intellij.openapi.actionSystem.ActionPlaces.MAIN_TOOLBAR, com.intellij.openapi.actionSystem.ActionUiKind.TOOLBAR, null,
            )
            manager.getAction(id).update(event)
            return presentation.isEnabled to presentation.text.orEmpty()
        }
        assertEquals(true to "Start", enabled("ClockedIn.ClockIn"))
        assertFalse(enabled("ClockedIn.Pause").first)
        assertFalse(enabled("ClockedIn.ClockOut").first)
        ClockService.getInstance().clockIn(Actor.ME)
        assertFalse(enabled("ClockedIn.ClockIn").first)
        assertTrue(enabled("ClockedIn.Pause").first)
        assertTrue(enabled("ClockedIn.ClockOut").first)
        ClockService.getInstance().pause(Actor.ME)
        assertEquals(true to "Resume", enabled("ClockedIn.ClockIn"))
        assertFalse(enabled("ClockedIn.Pause").first)
        assertTrue(enabled("ClockedIn.ClockOut").first)
    }

    fun `test every icon in plugin xml exists in the IDE`() {
        val xml = javaClass.classLoader.getResource("META-INF/plugin.xml")!!.readText()
        val icons = Regex("""icon="AllIcons\.([A-Za-z.]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertTrue(icons.isNotEmpty())
        for (icon in icons) {
            val group = "com.intellij.icons.AllIcons$" + icon.substringBeforeLast('.').replace('.', '$')
            val field = icon.substringAfterLast('.')
            val found = runCatching { Class.forName(group).getField(field).get(null) }.getOrNull()
            assertNotNull("AllIcons.$icon doesn't exist in this IDE", found)
        }
    }

    fun `test summary window is called This Week`() {
        assertEquals("This Week", ActionManager.getInstance().getAction("ClockedIn.Summary").templateText)
        assertEquals("This Week", com.sterbenden.clockedin.ui.ClockUi.SUMMARY_TITLE)
    }

    fun `test All dates in export and print runs from the first session to today`() {
        val zone = java.time.ZoneId.of("UTC")
        val today = java.time.LocalDate.parse("2026-11-22")
        val first = java.time.LocalDate.parse("2026-03-04").atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val sessions = listOf(com.sterbenden.clockedin.core.Session("a", first, first + 1000, Actor.ME, Actor.ME))
        val dialog = com.sterbenden.clockedin.ui.DateRangeDialog(project, "Test", today, today)
        try {
            assertEquals("All dates", dialog.allDatesBox.text)
            dialog.allDatesBox.isSelected = true
            assertTrue(dialog.allDates)
            assertEquals(java.time.LocalDate.parse("2026-03-04") to today, dialog.resolve(sessions, zone, today))
            assertEquals(today to today, dialog.resolve(emptyList(), zone, today))
        } finally {
            com.intellij.openapi.util.Disposer.dispose(dialog.disposable)
        }
    }

    fun `test status bar clock is off by default`() {
        assertFalse(com.sterbenden.clockedin.ui.ClockStatusBarWidgetFactory().isEnabledByDefault)
    }

    fun `test agent names are made readable`() {
        val ui = com.sterbenden.clockedin.ui.ClockUi
        assertEquals("Claude Code", ui.agentName("claude-code"))
        assertEquals("Cursor", ui.agentName("cursor"))
        assertEquals("Junie", ui.agentName("Junie"))
        assertEquals("GitHub Copilot", ui.agentName("GitHub Copilot"))
        assertNull(ui.agentName("  "))
        assertEquals("Claude Code clocked", com.sterbenden.clockedin.service.ClockNotifier.agentLabel("claude-code") + " clocked")
    }

    fun `test notification group is registered`() {
        assertNotNull(NotificationGroupManager.getInstance().getNotificationGroup("Clocked In"))
    }

    fun `test status bar text shows state and today's total`() {
        val widget = ClockStatusBarWidget()
        try {
            assertEquals("○ 00:00:00", widget.getText())
            ClockService.getInstance().clockIn(Actor.ME)
            assertEquals("● 00:00:00", widget.getText())
            assertTrue(widget.getTooltipText().contains("Clocked in since"))
        } finally {
            widget.dispose()
        }
    }

    fun `test tool window content builds and follows the clock`() {
        val toolWindow = com.intellij.toolWindow.ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        ClockToolWindowFactory().createToolWindowContent(project, toolWindow)
        assertEquals(1, toolWindow.contentManager.contentCount)
        ClockService.getInstance().clockIn(Actor.AGENT, "Claude Code")
        ClockService.getInstance().clockOut(Actor.ME)
        com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, ClockService.getInstance().sessions().size)
    }

    fun `test skill installer writes the bundled skill`() {
        val dir = FileUtil.createTempDirectory("clocked-in-skill", null).toPath()
        val target = SkillFiles.projectTarget(dir)
        InstallSkillAction.writeTo(project, target, askBeforeReplacing = false)
        assertEquals(SkillFiles.bundled, Files.readString(target))
        assertEquals(SkillFiles.State.UP_TO_DATE, SkillFiles.stateOf(target))
    }
}
