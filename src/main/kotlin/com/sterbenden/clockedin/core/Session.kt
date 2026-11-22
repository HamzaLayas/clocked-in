package com.sterbenden.clockedin.core

import kotlinx.serialization.Serializable

/** Who started, paused or stopped the clock. */
@Serializable
enum class Actor {
    /** The developer, through the IDE UI. */
    ME,

    /** An AI agent, through the MCP tools. */
    AGENT,

    /** The IDE itself: shutdown or crash recovery. */
    SYSTEM,
}

/** What the clock is doing right now. */
enum class ClockState {
    /** A session is open and counting. */
    RUNNING,

    /** A session is open but on a break: Resume continues the same session. */
    PAUSED,

    /** Clocked out: the next Start begins a new session. */
    STOPPED,
}

/** A break inside a session. [end] is `null` while the break is going on. */
@Serializable
data class Break(
    val start: Long,
    val end: Long? = null,
    val by: Actor,
    /** MCP client name when an agent took the break. */
    val agent: String? = null,
)

/**
 * One work session, from Start to Stop, with any breaks taken in between. Times are epoch
 * milliseconds. [end] is `null` while the session is open (running or on a break).
 */
@Serializable
data class Session(
    val id: String,
    val start: Long,
    val end: Long? = null,
    val startedBy: Actor,
    val stoppedBy: Actor? = null,
    /** MCP client name when an agent started the session, e.g. "claude-code". */
    val startedByAgent: String? = null,
    /** MCP client name when an agent stopped the session. */
    val stoppedByAgent: String? = null,
    /** Set by the developer once this time has been paid. */
    val paid: Boolean = false,
    /** Breaks in time order. Only the last one can still be open, and only while the session is. */
    val breaks: List<Break> = emptyList(),
) {
    /** Open: not stopped yet. It may be running or on a break. */
    val isOpen: Boolean get() = end == null

    /** The break in progress, if the session is open and on a break. */
    val openBreak: Break? get() = if (end == null) breaks.lastOrNull()?.takeIf { it.end == null } else null

    /** Open and not on a break. */
    val isRunning: Boolean get() = end == null && openBreak == null

    /** End used for calculations: the real end, or [now] while open. */
    fun effectiveEnd(now: Long): Long = end ?: maxOf(now, start)

    /** Break time inside [from, to). An open break counts up to [now]. */
    fun breakMsBetween(from: Long, to: Long, now: Long): Long {
        val sessionEnd = effectiveEnd(now)
        return breaks.sumOf { b -> overlap(b.start, minOf(b.end ?: sessionEnd, sessionEnd), from, to) }
    }

    /** Worked time inside [from, to): the session's time there minus its breaks there. */
    fun workedMsBetween(from: Long, to: Long, now: Long): Long =
        (overlap(start, effectiveEnd(now), from, to) - breakMsBetween(from, to, now)).coerceAtLeast(0)

    /** Total worked time, without breaks. */
    fun durationMs(now: Long): Long = workedMsBetween(Long.MIN_VALUE, Long.MAX_VALUE, now)

    /** Total break time. */
    fun breakMs(now: Long): Long = breakMsBetween(Long.MIN_VALUE, Long.MAX_VALUE, now)

    private fun overlap(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Long =
        (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0)
}

/** Which sessions to show or export, by paid status. */
enum class PaidFilter(val label: String) {
    ALL("All"),
    UNPAID("Unpaid"),
    PAID("Paid"),
    ;

    fun matches(session: Session): Boolean = when (this) {
        ALL -> true
        UNPAID -> !session.paid
        PAID -> session.paid
    }

    override fun toString() = label
}

/** A break as stored by earlier versions, where pausing ended the session. Read only for migration. */
@Serializable
data class Pause(
    val by: Actor,
    val agent: String? = null,
    val at: Long,
)

/** Everything that is persisted. */
@Serializable
data class LedgerData(
    val version: Int = 1,
    val sessions: List<Session> = emptyList(),
    /** Last time the running session was confirmed alive; used for crash recovery. */
    val heartbeat: Long? = null,
    /** Only in files from earlier versions; migrated into a break on load. */
    val pause: Pause? = null,
)

/**
 * A reversible change. Undo puts [before] back (or removes the session when it's null),
 * but only while the session still looks exactly like [after].
 */
data class ClockEvent(
    val id: String,
    val kind: Kind,
    val sessionId: String,
    /** When the change happened (the start, pause, resume or stop time). */
    val at: Long,
    val before: Session?,
    val after: Session,
) {
    enum class Kind { CLOCK_IN, RESUME, PAUSE, CLOCK_OUT }
}
