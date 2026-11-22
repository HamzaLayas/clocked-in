package com.sterbenden.clockedin.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale

/** Hours worked on one day, in whole seconds. */
data class DayHours(val date: LocalDate, val seconds: Long)

/** One ISO week (Monday to Sunday) and the days in it that have unpaid work. */
data class WeekHours(val monday: LocalDate, val days: List<DayHours>) {
    val sunday: LocalDate get() = monday.plusDays(6)
    val seconds: Long get() = days.sumOf { it.seconds }
    val week: Int get() = monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    val year: Int get() = monday.get(IsoFields.WEEK_BASED_YEAR)
}

/**
 * Every unpaid session, as hours per day grouped into weeks, for handing in. Sessions that
 * cross midnight count towards each day they cover. Breaks are not counted. An open session
 * counts up to [now]. Each day is rounded down to whole seconds and the week and grand totals
 * are sums of the days, so the numbers on the sheet always add up.
 */
object UnpaidHours {
    fun weeks(sessions: List<Session>, zone: ZoneId, now: Long): List<WeekHours> {
        val unpaid = sessions.filter { !it.paid }
        if (unpaid.isEmpty()) return emptyList()
        val from = unpaid.minOf { date(it.start, zone) }
        val to = unpaid.maxOf { date(it.effectiveEnd(now), zone) }
        val days = Reports.dayPieces(unpaid, from, to, zone, now)
            .groupBy { it.date }
            .map { (date, pieces) -> DayHours(date, Formats.seconds(pieces.sumOf { it.workedMs })) }
            .filter { it.seconds > 0 }
            .sortedBy { it.date }
        return days.groupBy { Reports.weekStart(it.date) }.map { (monday, inWeek) -> WeekHours(monday, inWeek) }
    }

    private val DAY_TITLE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /** A workbook with a Summary sheet listing each week, then one sheet per week listing its days. */
    fun workbook(weeks: List<WeekHours>, exportedOn: LocalDate): List<Xlsx.Sheet> {
        val total = weeks.sumOf { it.seconds }
        val summary = buildList<List<Xlsx.Cell>> {
            add(listOf(Xlsx.Cell.Text("Unpaid hours", bold = true)))
            add(listOf(Xlsx.Cell.Text("Exported ${exportedOn.format(Formats.DATE)}")))
            add(emptyList())
            add(listOf("Week", "From", "To", "Hours").map { Xlsx.Cell.Text(it, bold = true) })
            for (w in weeks) {
                add(
                    listOf(
                        Xlsx.Cell.Text(weekLabel(w)),
                        Xlsx.Cell.Text(w.monday.format(Formats.DATE)),
                        Xlsx.Cell.Text(w.sunday.format(Formats.DATE)),
                        Xlsx.Cell.Duration(w.seconds),
                    ),
                )
            }
            add(listOf(Xlsx.Cell.Text("Total", bold = true), Xlsx.Cell.Text(""), Xlsx.Cell.Text(""), Xlsx.Cell.Duration(total, bold = true)))
        }
        val sheets = mutableListOf(Xlsx.Sheet("Summary", summary, listOf(18, 12, 12, 12)))
        for (w in weeks) {
            val rows = buildList<List<Xlsx.Cell>> {
                add(listOf(Xlsx.Cell.Text(weekLabel(w), bold = true)))
                add(listOf(Xlsx.Cell.Text("${w.monday.format(DAY_TITLE)} – ${w.sunday.format(DAY_TITLE)}")))
                add(emptyList())
                add(listOf("Date", "Day", "Hours").map { Xlsx.Cell.Text(it, bold = true) })
                for (d in w.days) {
                    add(
                        listOf(
                            Xlsx.Cell.Text(d.date.format(Formats.DATE)),
                            Xlsx.Cell.Text(d.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)),
                            Xlsx.Cell.Duration(d.seconds),
                        ),
                    )
                }
                add(listOf(Xlsx.Cell.Text("Week total", bold = true), Xlsx.Cell.Text(""), Xlsx.Cell.Duration(w.seconds, bold = true)))
            }
            sheets += Xlsx.Sheet("${w.year}-W${"%02d".format(w.week)}", rows, listOf(14, 12, 12))
        }
        return sheets
    }

    fun weekLabel(w: WeekHours): String = "Week ${w.week}, ${w.year}"

    private fun date(epochMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
}
