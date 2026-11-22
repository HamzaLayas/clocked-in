package com.sterbenden.clockedin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import com.sterbenden.clockedin.service.ClockListener
import com.sterbenden.clockedin.service.ClockService
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.Timer

class ClockStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ClockStatusBarWidget.ID

    override fun getDisplayName(): String = "Clocked In"

    // The clock lives in the main toolbar; the status bar copy is opt-in (right-click the status bar).
    override fun isEnabledByDefault(): Boolean = false

    override fun createWidget(project: Project): StatusBarWidget = ClockStatusBarWidget()
}

/** Optional status bar copy of the clock: "● 03:12:45". Click to pause, or to start/resume. */
class ClockStatusBarWidget : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var statusBar: StatusBar? = null
    private val timer = Timer(REFRESH_MS) { refresh() }

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(ClockListener.TOPIC, ClockListener { refresh() })
        timer.start()
    }

    override fun dispose() {
        timer.stop()
        statusBar = null
    }

    // The status bar has no buttons next to it, so the symbol shows the state.
    override fun getText(): String = ClockUi.clockTextWithState(ClockService.getInstance().status())

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String = ClockUi.tooltip(ClockService.getInstance().status()).replace("\n", " · ")

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { ClockUi.toggle() }

    private fun refresh() {
        statusBar?.updateWidget(ID)
    }

    companion object {
        const val ID = "ClockedInWidget"

        /** Every second, so the hh:mm:ss display keeps ticking. */
        const val REFRESH_MS = 1_000
    }
}
