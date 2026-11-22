package com.sterbenden.clockedin.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.messages.Topic
import com.sterbenden.clockedin.core.Actor
import com.sterbenden.clockedin.core.ClockEvent
import com.sterbenden.clockedin.core.ClockInResult
import com.sterbenden.clockedin.core.ClockLedger
import com.sterbenden.clockedin.core.ClockOutResult
import com.sterbenden.clockedin.core.ClockState
import com.sterbenden.clockedin.core.Break
import com.sterbenden.clockedin.core.PauseResult
import com.sterbenden.clockedin.core.EditResult
import com.sterbenden.clockedin.core.Reports
import com.sterbenden.clockedin.core.Session
import com.sterbenden.clockedin.core.SessionStore
import com.sterbenden.clockedin.core.Totals
import com.sterbenden.clockedin.core.UndoResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** Fired on the application message bus after any change to the clock or its history. */
fun interface ClockListener {
    fun clockChanged()

    companion object {
        @JvmField
        @Topic.AppLevel
        val TOPIC: Topic<ClockListener> = Topic(ClockListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

data class ClockStatus(
    /** The open session, running or on a break; null when clocked out. */
    val running: Session?,
    val today: Totals,
    val week: Totals,
    val now: Long,
) {
    /** The break in progress, if any. */
    val pause: Break? get() = running?.openBreak

    val state: ClockState
        get() = when {
            running == null -> ClockState.STOPPED
            pause != null -> ClockState.PAUSED
            else -> ClockState.RUNNING
        }

    /** Worked time in the open session, without breaks; 0 when clocked out. */
    val sessionWorkedMs: Long get() = running?.durationMs(now) ?: 0
}

/**
 * The one work clock for this IDE. Every change made from the UI or MCP goes through here.
 * Data lives in `<IDE config>/clocked-in/sessions.json`.
 */
@Service(Service.Level.APP)
class ClockService(scope: CoroutineScope) {
    private val lock = Any()
    private val store = SessionStore(PathManager.getConfigDir().resolve("clocked-in").resolve("sessions.json"))
    private val ledger: ClockLedger

    @Volatile
    private var shutDown = false

    init {
        val data = store.load()
        store.lastLoadProblem?.let { thisLogger().warn(it) }
        ledger = ClockLedger(data)
        ledger.recoverAfterCrash()?.let {
            thisLogger().info("Closed a session left running by an unclean shutdown at ${Instant.ofEpochMilli(it.end!!)}")
            persist()
        }
        scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                synchronized(lock) {
                    if (!shutDown && ledger.beat(now())) persist()
                }
            }
        }
    }

    val zone: ZoneId get() = ZoneId.systemDefault()

    fun sessions(): List<Session> = synchronized(lock) { ledger.sessions }

    fun status(): ClockStatus = synchronized(lock) {
        val now = now()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val sessions = ledger.sessions
        ClockStatus(ledger.open, Reports.day(sessions, today, zone, now), Reports.week(sessions, today, zone, now), now)
    }

    fun clockIn(actor: Actor, agentName: String? = null): ClockInResult = mutate {
        ledger.clockIn(actor, now(), agentName).also { changed = it is ClockInResult.Started }
    }

    fun pause(actor: Actor, agentName: String? = null): PauseResult = mutate {
        ledger.pause(actor, now(), agentName).also { changed = it is PauseResult.Paused }
    }

    fun clockOut(actor: Actor, agentName: String? = null): ClockOutResult = mutate {
        ledger.clockOut(actor, now(), agentName).also { changed = it is ClockOutResult.Stopped }
    }

    fun undo(event: ClockEvent): UndoResult = mutate {
        ledger.undo(event).also { changed = it == UndoResult.Undone }
    }

    fun add(start: Long, end: Long): EditResult = mutate {
        ledger.add(start, end, now()).also { changed = it is EditResult.Ok }
    }

    fun edit(id: String, start: Long, end: Long?): EditResult = mutate {
        ledger.edit(id, start, end, now()).also { changed = it is EditResult.Ok }
    }

    fun setPaid(ids: Collection<String>, paid: Boolean): Int = mutate {
        ledger.setPaid(ids, paid).also { changed = it > 0 }
    }

    fun delete(id: String): Boolean = mutate {
        ledger.delete(id).also { changed = it }
    }

    /** Deletes several sessions at once. Returns how many were deleted. */
    fun delete(ids: Collection<String>): Int = mutate {
        ids.count { ledger.delete(it) }.also { changed = it > 0 }
    }

    /** Called when the IDE is closing: a running session or a break ends now. */
    fun shutdown() {
        mutate {
            shutDown = true
            ledger.clockOut(Actor.SYSTEM, now()).also { changed = it is ClockOutResult.Stopped }
        }
    }

    private class Mutation {
        var changed = false
    }

    private inline fun <T> mutate(block: Mutation.() -> T): T {
        val mutation = Mutation()
        val result = synchronized(lock) {
            mutation.block().also { if (mutation.changed) persist() }
        }
        if (mutation.changed) {
            val app = ApplicationManager.getApplication()
            app.invokeLater { app.messageBus.syncPublisher(ClockListener.TOPIC).clockChanged() }
        }
        return result
    }

    private fun persist() {
        try {
            store.save(ledger.snapshot())
        } catch (e: Exception) {
            thisLogger().error("Could not save Clocked In sessions", e)
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private const val HEARTBEAT_MS = 60_000L

        fun getInstance(): ClockService = service()
    }
}
