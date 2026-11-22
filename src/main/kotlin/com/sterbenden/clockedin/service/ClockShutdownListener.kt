package com.sterbenden.clockedin.service

import com.intellij.ide.AppLifecycleListener

/** Ends a running session when the IDE closes, so the clock never runs while the IDE is off. */
class ClockShutdownListener : AppLifecycleListener {
    override fun appWillBeClosed(isRestart: Boolean) {
        ClockService.getInstance().shutdown()
    }
}
