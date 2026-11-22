package com.sterbenden.clockedin.core

import java.util.UUID

sealed interface ClockInResult {
    /** A session started, or ([resumed]) the open session came back from a break. */
    data class Started(val session: Session, val event: ClockEvent, val resumed: Boolean) : ClockInResult
    data class AlreadyRunning(val session: Session) : ClockInResult

    /** An agent tried to end a break the developer took; nothing changed. */
    data class OnBreak(val pause: Break) : ClockInResult
}

sealed interface PauseResult {
    data class Paused(val session: Session, val event: ClockEvent) : PauseResult
    data class AlreadyPaused(val pause: Break) : PauseResult
    data object NotRunning : PauseResult

    /** An agent tried to pause a session the developer started; it keeps running. */
    data class LeftRunning(val session: Session) : PauseResult
}

sealed interface ClockOutResult {
    data class Stopped(val session: Session, val event: ClockEvent) : ClockOutResult
    data object NotRunning : ClockOutResult

    /** An agent tried to stop a session the developer started; it keeps running. */
    data class LeftRunning(val session: Session) : ClockOutResult

    /** An agent tried to end a break the developer took; nothing changed. */
    data class LeftOnBreak(val pause: Break) : ClockOutResult
}

sealed interface UndoResult {
    data object Undone : UndoResult
    data class NotPossible(val reason: String) : UndoResult
}

sealed interface EditResult {
    data class Ok(val session: Session) : EditResult
    data class Invalid(val reason: String) : EditResult
}

/**
 * The single work clock and its history. Not thread-safe: callers serialize access.
 *
 * A session runs from Start to Stop. Pause adds a break inside it and Resume ends that
 * break, so the same session continues. Invariants: sessions never overlap, and at most
 * one session is open.
 *
 * Agents can't undo what the developer did: they can't pause or stop a session the
 * developer started, and they can't end a break the developer took.
 */
class ClockLedger(
    data: LedgerData = LedgerData(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val list: MutableList<Session> = normalize(migrate(data))

    var heartbeat: Long? = data.heartbeat
        private set

    val sessions: List<Session> get() = list.toList()

    /** The session that hasn't been stopped yet, running or on a break. */
    val open: Session? get() = list.lastOrNull { it.isOpen }

    val state: ClockState
        get() {
            val s = open ?: return ClockState.STOPPED
            return if (s.openBreak != null) ClockState.PAUSED else ClockState.RUNNING
        }

    fun snapshot(): LedgerData = LedgerData(sessions = sessions, heartbeat = heartbeat)

    /** Starts a new session, or resumes the open one after a break. */
    fun clockIn(actor: Actor, now: Long, agentName: String? = null): ClockInResult {
        val current = open
        if (current != null) {
            val pause = current.openBreak ?: return ClockInResult.AlreadyRunning(current)
            if (actor == Actor.AGENT && pause.by == Actor.ME) return ClockInResult.OnBreak(pause)
            val resumed = current.copy(breaks = current.breaks.dropLast(1) + pause.copy(end = maxOf(now, pause.start)))
            replace(resumed)
            heartbeat = now
            return ClockInResult.Started(resumed, event(ClockEvent.Kind.RESUME, now, current, resumed), resumed = true)
        }
        // Never start before the previous session ended, even if the system clock moved back.
        val start = maxOf(now, list.maxOfOrNull { it.end ?: it.start } ?: now)
        val session = Session(
            id = newId(),
            start = start,
            startedBy = actor,
            startedByAgent = agentName.takeIf { actor == Actor.AGENT },
        )
        list += session
        heartbeat = now
        return ClockInResult.Started(session, event(ClockEvent.Kind.CLOCK_IN, start, null, session), resumed = false)
    }

    /** Starts a break in the running session. */
    fun pause(actor: Actor, now: Long, agentName: String? = null): PauseResult {
        val current = open ?: return PauseResult.NotRunning
        current.openBreak?.let { return PauseResult.AlreadyPaused(it) }
        if (actor == Actor.AGENT && current.startedBy == Actor.ME) return PauseResult.LeftRunning(current)
        val at = maxOf(now, current.start, current.breaks.maxOfOrNull { it.end ?: it.start } ?: current.start)
        val paused = current.copy(breaks = current.breaks + Break(at, null, actor, agentName.takeIf { actor == Actor.AGENT }))
        replace(paused)
        return PauseResult.Paused(paused, event(ClockEvent.Kind.PAUSE, at, current, paused))
    }

    /** Ends the open session. Stopping during a break ends the session when the break began. */
    fun clockOut(actor: Actor, now: Long, agentName: String? = null): ClockOutResult {
        val current = open ?: return ClockOutResult.NotRunning
        val pause = current.openBreak
        if (actor == Actor.AGENT) {
            if (pause == null && current.startedBy == Actor.ME) return ClockOutResult.LeftRunning(current)
            if (pause != null && pause.by == Actor.ME) return ClockOutResult.LeftOnBreak(pause)
        }
        val closed = current.copy(
            end = pause?.start ?: maxOf(now, current.start),
            breaks = if (pause != null) current.breaks.dropLast(1) else current.breaks,
            stoppedBy = actor,
            stoppedByAgent = agentName.takeIf { actor == Actor.AGENT },
        )
        replace(closed)
        heartbeat = null
        return ClockOutResult.Stopped(closed, event(ClockEvent.Kind.CLOCK_OUT, closed.end!!, current, closed))
    }

    /** Records that the running session is still alive. Returns false when nothing is running. */
    fun beat(now: Long): Boolean {
        val current = open?.takeIf { it.isRunning } ?: return false
        heartbeat = maxOf(now, current.start)
        return true
    }

    /**
     * Called at startup. An open session means the IDE did not shut down cleanly: it is
     * closed when its break began, or otherwise at the last heartbeat.
     */
    fun recoverAfterCrash(): Session? {
        val current = open ?: return null
        val pause = current.openBreak
        val closed = current.copy(
            end = pause?.start ?: maxOf(current.start, heartbeat ?: current.start),
            breaks = if (pause != null) current.breaks.dropLast(1) else current.breaks,
            stoppedBy = Actor.SYSTEM,
        )
        replace(closed)
        heartbeat = null
        return closed
    }

    fun undo(event: ClockEvent): UndoResult {
        val current = find(event.sessionId) ?: return UndoResult.NotPossible("That session no longer exists.")
        if (current != event.after) return UndoResult.NotPossible("That session was changed since.")
        val before = event.before
        if (before == null) {
            list.remove(current)
            if (current.isOpen) heartbeat = null
            return UndoResult.Undone
        }
        if (before.isOpen && list.any { it.id != current.id && (it.isOpen || it.start >= current.start) }) {
            return UndoResult.NotPossible("A newer session exists.")
        }
        replace(before)
        heartbeat = if (before.isRunning) event.at else null
        return UndoResult.Undone
    }

    /** Adds a finished session by hand. */
    fun add(start: Long, end: Long, now: Long): EditResult {
        val candidate = Session(id = newId(), start = start, end = end, startedBy = Actor.ME, stoppedBy = Actor.ME)
        validate(candidate, now)?.let { return EditResult.Invalid(it) }
        list += candidate
        list.sortBy { it.start }
        return EditResult.Ok(candidate)
    }

    /** Changes a session's times. [end] may be null only for the open session. */
    fun edit(id: String, start: Long, end: Long?, now: Long): EditResult {
        val existing = find(id) ?: return EditResult.Invalid("That session no longer exists.")
        if (end == null && !existing.isOpen) return EditResult.Invalid("Only the open session can have no end.")
        if (end != null && existing.isOpen) return EditResult.Invalid("Stop the clock to set an end for the open session.")
        val candidate = existing.copy(start = start, end = end)
        validate(candidate, now)?.let { return EditResult.Invalid(it) }
        replace(candidate)
        list.sortBy { it.start }
        return EditResult.Ok(candidate)
    }

    /** Marks sessions as paid or unpaid. Returns how many actually changed. */
    fun setPaid(ids: Collection<String>, paid: Boolean): Int {
        var changed = 0
        for (i in list.indices) {
            val s = list[i]
            if (s.id in ids && s.paid != paid) {
                list[i] = s.copy(paid = paid)
                changed++
            }
        }
        return changed
    }

    fun delete(id: String): Boolean {
        val session = find(id) ?: return false
        list.remove(session)
        if (session.isOpen) heartbeat = null
        return true
    }

    private fun event(kind: ClockEvent.Kind, at: Long, before: Session?, after: Session) =
        ClockEvent(newId(), kind, after.id, at, before, after)

    private fun validate(candidate: Session, now: Long): String? {
        val end = candidate.end
        if (candidate.start > now) return "The start can't be in the future."
        if (end != null && end <= candidate.start) return "The end must be after the start."
        if (end != null && end > now) return "The end can't be in the future."
        val limit = end ?: Long.MAX_VALUE
        if (candidate.breaks.any { it.start < candidate.start || (it.end ?: it.start) > limit }) {
            return "A break falls outside these times."
        }
        val clash = list.firstOrNull { other ->
            other.id != candidate.id &&
                candidate.start < (other.end ?: Long.MAX_VALUE) &&
                other.start < limit
        }
        return if (clash != null) "It overlaps another session." else null
    }

    private fun find(id: String): Session? = list.firstOrNull { it.id == id }

    private fun replace(session: Session) {
        val index = list.indexOfFirst { it.id == session.id }
        check(index >= 0) { "Unknown session ${session.id}" }
        list[index] = session
    }

    private companion object {
        /**
         * Earlier versions ended the session on Pause and stored the break separately. Turn
         * such a break back into an open session with a break in progress.
         */
        fun migrate(data: LedgerData): List<Session> {
            val pause = data.pause ?: return data.sessions
            if (data.sessions.any { it.isOpen }) return data.sessions
            val last = data.sessions.maxByOrNull { it.start } ?: return data.sessions
            if (last.end != pause.at) return data.sessions
            val reopened = last.copy(
                end = null,
                stoppedBy = null,
                stoppedByAgent = null,
                breaks = last.breaks + Break(pause.at, null, pause.by, pause.agent),
            )
            return data.sessions.map { if (it.id == last.id) reopened else it }
        }

        /** Repairs data that breaks the single-open-session invariant (e.g. a hand-edited file). */
        fun normalize(sessions: List<Session>): MutableList<Session> {
            val sorted = sessions.sortedBy { it.start }.toMutableList()
            for (i in 0 until sorted.size - 1) {
                val s = sorted[i]
                if (s.isOpen) {
                    val end = maxOf(s.start, sorted[i + 1].start)
                    sorted[i] = s.copy(
                        end = end,
                        stoppedBy = Actor.SYSTEM,
                        breaks = s.breaks.map { b -> if (b.end == null) b.copy(end = maxOf(b.start, end)) else b },
                    )
                }
            }
            return sorted
        }
    }
}
