package com.sterbenden.clockedin.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** Worked time, split by who started each session, plus how much of it is unpaid. */
data class Totals(
    val totalMs: Long = 0,
    val meStartedMs: Long = 0,
    val agentStartedMs: Long = 0,
    val unpaidMs: Long = 0,
) {
    operator fun plus(other: Totals) = Totals(
        totalMs + other.totalMs,
        meStartedMs + other.meStartedMs,
        agentStartedMs + other.agentStartedMs,
        unpaidMs + other.unpaidMs,
    )
}

enum class GroupBy { DAY, WEEK }

data class PeriodTotals(val start: LocalDate, val endInclusive: LocalDate, val totals: Totals)

/** A session cut to a single calendar day, with the work and break time on that day. */
data class DayPiece(
    val date: LocalDate,
    val start: Long,
    val end: Long,
    val session: Session,
    val workedMs: Long,
    val breakMs: Long,
)

object Reports {
    /** Total time worked in the half-open interval [from, to). Running sessions count up to [now]. */
    fun totals(sessions: List<Session>, from: Long, to: Long, now: Long): Totals =
        sessions.fold(Totals()) { acc, s ->
            val ms = s.workedMsBetween(from, to, now)
            val unpaid = if (s.paid) 0 else ms
            acc + when (s.startedBy) {
                Actor.AGENT -> Totals(ms, 0, ms, unpaid)
                else -> Totals(ms, ms, 0, unpaid)
            }
        }

    fun day(sessions: List<Session>, date: LocalDate, zone: ZoneId, now: Long): Totals =
        totals(sessions, startOf(date, zone), startOf(date.plusDays(1), zone), now)

    /** The ISO week (Monday to Sunday) containing [date]. */
    fun week(sessions: List<Session>, date: LocalDate, zone: ZoneId, now: Long): Totals {
        val monday = weekStart(date)
        return totals(sessions, startOf(monday, zone), startOf(monday.plusWeeks(1), zone), now)
    }

    /** Totals for every day or ISO week between [from] and [to] inclusive, including empty periods. */
    fun report(
        sessions: List<Session>,
        from: LocalDate,
        to: LocalDate,
        groupBy: GroupBy,
        zone: ZoneId,
        now: Long,
    ): List<PeriodTotals> {
        require(!to.isBefore(from)) { "'to' must not be before 'from'" }
        val periods = mutableListOf<PeriodTotals>()
        var cursor = from
        while (!cursor.isAfter(to)) {
            val periodEnd = when (groupBy) {
                GroupBy.DAY -> cursor
                GroupBy.WEEK -> minOf(weekStart(cursor).plusDays(6), to)
            }
            val totals = totals(sessions, startOf(cursor, zone), startOf(periodEnd.plusDays(1), zone), now)
            periods += PeriodTotals(cursor, periodEnd, totals)
            cursor = periodEnd.plusDays(1)
        }
        return periods
    }

    /** Splits sessions at midnight and keeps the pieces that fall between [from] and [to] inclusive. */
    fun dayPieces(sessions: List<Session>, from: LocalDate, to: LocalDate, zone: ZoneId, now: Long): List<DayPiece> {
        val rangeStart = startOf(from, zone)
        val rangeEnd = startOf(to.plusDays(1), zone)
        val pieces = mutableListOf<DayPiece>()
        for (s in sessions.sortedBy { it.start }) {
            val start = maxOf(s.start, rangeStart)
            val end = minOf(s.effectiveEnd(now), rangeEnd)
            if (end <= start) continue
            var pieceStart = start
            while (true) {
                val date = Instant.ofEpochMilli(pieceStart).atZone(zone).toLocalDate()
                val nextMidnight = startOf(date.plusDays(1), zone)
                val pieceEnd = minOf(end, nextMidnight)
                pieces += DayPiece(
                    date, pieceStart, pieceEnd, s,
                    s.workedMsBetween(pieceStart, pieceEnd, now), s.breakMsBetween(pieceStart, pieceEnd, now),
                )
                if (pieceEnd >= end) break
                pieceStart = pieceEnd
            }
        }
        return pieces
    }

    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun startOf(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()
}

object Formats {
    val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** "0m", "45m", "3h 05m". */
    fun duration(ms: Long): String {
        val minutes = ms.coerceAtLeast(0) / 60_000
        val h = minutes / 60
        val m = minutes % 60
        return if (h == 0L) "${m}m" else "${h}h ${m.toString().padStart(2, '0')}m"
    }

    fun minutes(ms: Long): Long = ms.coerceAtLeast(0) / 60_000

    /** "00:00:00", "03:12:45". Hours keep counting past 24. */
    fun clock(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
    }

    /** Turns client ids like "claude-code" into "Claude Code"; names with capitals are kept as they are. */
    fun agentName(raw: String?): String? {
        val name = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (name.any { it.isUpperCase() }) return name
        return name.split('-', '_', ' ').filter { it.isNotEmpty() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }
    }

    fun dateTime(epochMs: Long, zone: ZoneId): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone).format(DATE_TIME)

    fun time(epochMs: Long, zone: ZoneId): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone).format(TIME)

    fun isoInstant(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    /** Parses "yyyy-MM-dd HH:mm" in [zone]. Returns null when the text is not a valid date and time. */
    fun parseDateTime(text: String, zone: ZoneId): Long? = runCatching {
        LocalDateTime.parse(text.trim(), DATE_TIME).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim(), DATE) }.getOrNull()
}
