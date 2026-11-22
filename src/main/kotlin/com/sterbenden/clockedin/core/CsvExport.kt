package com.sterbenden.clockedin.core

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * CSV export, one row per session per day. Sessions that cross midnight are split so every
 * row belongs to one day. `minutes` and `hours` are worked time, without breaks. An open
 * session is exported up to [now] with `stopped_by` set to `running` or `on_break`.
 */
object CsvExport {
    const val HEADER = "date,start,end,break_minutes,minutes,hours,started_by,stopped_by,agent,paid"

    fun export(
        sessions: List<Session>,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
        now: Long,
        paidFilter: PaidFilter = PaidFilter.ALL,
    ): String {
        val sb = StringBuilder(HEADER).append('\n')
        for (piece in Reports.dayPieces(sessions.filter(paidFilter::matches), from, to, zone, now)) {
            val s = piece.session
            val ms = piece.workedMs
            val agent = listOfNotNull(s.startedByAgent, s.stoppedByAgent).distinct().joinToString(" / ")
            sb.append(piece.date.format(Formats.DATE)).append(',')
                .append(Formats.time(piece.start, zone)).append(',')
                .append(Formats.time(piece.end, zone)).append(',')
                .append(Formats.minutes(piece.breakMs)).append(',')
                .append(Formats.minutes(ms)).append(',')
                .append(String.format(Locale.ROOT, "%.2f", ms / 3_600_000.0)).append(',')
                .append(actor(s.startedBy)).append(',')
                .append(
                    when {
                        s.openBreak != null -> "on_break"
                        s.isOpen -> "running"
                        else -> actor(s.stoppedBy)
                    },
                ).append(',')
                .append(escape(agent)).append(',')
                .append(if (s.paid) "yes" else "no").append('\n')
        }
        return sb.toString()
    }

    private fun actor(actor: Actor?): String = actor?.name?.lowercase() ?: ""

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
}
