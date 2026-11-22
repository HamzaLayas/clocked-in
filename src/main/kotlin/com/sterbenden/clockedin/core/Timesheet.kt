package com.sterbenden.clockedin.core

import java.time.LocalDate
import java.time.ZoneId

/** A printable timesheet: every session per day, day totals, and a grand total. */
object Timesheet {
    /**
     * A standalone web page. With [autoPrint], the browser opens its print dialog as soon
     * as the page loads, where the user can also choose "Save as PDF".
     */
    fun html(sessions: List<Session>, from: LocalDate, to: LocalDate, zone: ZoneId, now: Long, autoPrint: Boolean = false): String {
        val pieces = Reports.dayPieces(sessions, from, to, zone, now)
        val total = Reports.totals(sessions, Reports.startOf(from, zone), Reports.startOf(to.plusDays(1), zone), now)
        val agentSeconds = Formats.seconds(total.agentStartedMs)
        val youSeconds = Formats.seconds(total.totalMs) - agentSeconds

        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
        sb.append("<title>Timesheet ").append(from.format(Formats.DATE)).append(" to ").append(to.format(Formats.DATE)).append("</title>")
        sb.append("<style>body{font-family:sans-serif;font-size:11pt;margin:24px}")
        sb.append("table{border-collapse:collapse}th,td{border:1px solid #999}th{background:#eee}")
        sb.append("@media print{body{margin:0}}</style>")
        if (autoPrint) sb.append("<script>window.addEventListener('load',function(){window.print()})</script>")
        sb.append("</head><body>")
        sb.append("<h2>Timesheet</h2>")
        sb.append("<p>").append(from.format(Formats.DATE)).append(" to ").append(to.format(Formats.DATE)).append("</p>")
        sb.append("<table border='1' cellspacing='0' cellpadding='4' width='100%'>")
        sb.append("<tr><th align='left'>Date</th><th align='left'>Start</th><th align='left'>End</th>")
        sb.append("<th align='right'>Breaks</th><th align='right'>Worked</th><th align='left'>Started by</th><th align='left'>Paid</th></tr>")
        if (pieces.isEmpty()) {
            sb.append("<tr><td colspan='7'>No sessions in this period.</td></tr>")
        }
        for ((date, day) in pieces.groupBy { it.date }) {
            for (piece in day) {
                val s = piece.session
                sb.append("<tr><td>").append(date.format(Formats.DATE)).append("</td>")
                    .append("<td>").append(Formats.time(piece.start, zone)).append("</td>")
                    .append("<td>").append(endLabel(piece, now, zone)).append("</td>")
                    .append("<td align='right'>").append(Formats.duration(piece.breakMs)).append("</td>")
                    .append("<td align='right'>").append(Formats.duration(piece.workedMs)).append("</td>")
                    .append("<td>").append(escape(startedBy(s))).append("</td>")
                    .append("<td>").append(if (s.paid) "Paid" else "").append("</td></tr>")
            }
            val dayMs = day.sumOf { it.workedMs }
            sb.append("<tr><td colspan='4'><b>").append(date.format(Formats.DATE)).append(" total</b></td>")
                .append("<td align='right'><b>").append(Formats.duration(dayMs)).append("</b></td><td></td><td></td></tr>")
        }
        sb.append("</table>")
        sb.append("<p><b>Total: ").append(Formats.duration(total.totalMs)).append("</b> (you ")
            .append(Formats.duration(youSeconds * 1000)).append(", agents ")
            .append(Formats.duration(agentSeconds * 1000)).append(")</p>")
        sb.append("<p style='font-size: 9pt'>Printed ").append(Formats.dateTime(now, zone)).append(" by Clocked In</p>")
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun endLabel(piece: DayPiece, now: Long, zone: ZoneId): String {
        val s = piece.session
        if (s.isOpen && piece.end >= now) return if (s.openBreak != null) "on a break" else "running"
        return Formats.time(piece.end, zone)
    }

    private fun startedBy(s: Session): String = when (s.startedBy) {
        Actor.ME -> "You"
        Actor.AGENT -> Formats.agentName(s.startedByAgent) ?: "Agent"
        Actor.SYSTEM -> "IDE"
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;")
}
