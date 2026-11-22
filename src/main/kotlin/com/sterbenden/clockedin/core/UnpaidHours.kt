package com.sterbenden.clockedin.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import com.sterbenden.clockedin.core.Xlsx.Style as S
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

    private val LONG_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
    private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /** `SUM(B9,B17,…)` for the week totals; Excel takes at most 255 arguments per function. */
    private fun grandTotal(cells: List<String>): String =
        cells.chunked(250).joinToString("+") { "SUM(${it.joinToString(",")})" }

    /** The first and last day with hours, for the title and the file name. */
    fun period(weeks: List<WeekHours>): Pair<LocalDate, LocalDate> =
        weeks.first().days.first().date to weeks.last().days.last().date

    /**
     * A timesheet workbook to hand in. The first sheet lists every day grouped by week, with
     * each week's total under its days and a grand total. Each week then has its own sheet
     * (one printed page) listing its days and their total. Totals
     * are formulas, so they can be checked in the spreadsheet. [name] is shown under the
     * title when it isn't blank.
     */
    fun workbook(weeks: List<WeekHours>, preparedOn: LocalDate, name: String = ""): List<Xlsx.Sheet> {
        require(weeks.isNotEmpty()) { "There are no hours to put on a timesheet." }
        val (first, last) = period(weeks)
        val sheetNames = weekSheetNames(weeks)
        val footer = listOf("Timesheet", name.trim()).filter { it.isNotEmpty() }.joinToString(" · ")

        fun header(rows: MutableList<List<Xlsx.Cell>>, title: String, period: String) {
            rows += listOf(Xlsx.Cell.Text(title, S.TITLE))
            if (name.isNotBlank()) rows += listOf(Xlsx.Cell.Text("Name", S.LABEL), Xlsx.Cell.Text(name.trim(), S.VALUE))
            rows += listOf(Xlsx.Cell.Text("Period", S.LABEL), Xlsx.Cell.Text(period, S.VALUE))
            rows += listOf(Xlsx.Cell.Text("Prepared", S.LABEL), Xlsx.Cell.Date(preparedOn, S.VALUE_DATE))
            rows += emptyList<Xlsx.Cell>()
        }

        val weekSheets = weeks.mapIndexed { index, w ->
            val rows = mutableListOf<List<Xlsx.Cell>>()
            header(rows, weekLabel(w), "${w.monday.format(LONG_DATE)} – ${w.sunday.format(LONG_DATE)}")
            rows += listOf(Xlsx.Cell.Text("Date", S.HEADER), Xlsx.Cell.Text("Hours", S.HEADER_RIGHT))
            val headerRows = rows.size
            for (d in w.days) rows += listOf(Xlsx.Cell.Date(d.date, S.CELL_DATE), Xlsx.Cell.Hours(d.seconds, S.CELL_HOURS))
            val firstDay = Xlsx.address(1, headerRows)
            val lastDay = Xlsx.address(1, rows.size - 1)
            rows += listOf(
                Xlsx.Cell.Text("Week total", S.TOTAL),
                Xlsx.Cell.HoursFormula("SUM($firstDay:$lastDay)", w.seconds, S.TOTAL_HOURS),
            )
            rows += emptyList<Xlsx.Cell>()
            rows += listOf(Xlsx.Cell.Text("Hours are hours:minutes:seconds worked, not counting breaks.", S.NOTE))
            Xlsx.Sheet(sheetNames[index], rows, widths = listOf(34, 14), freezeRows = headerRows, footer = footer)
        }

        // Summary: every day, grouped by week, each week followed by its total, then the grand total.
        val summary = mutableListOf<List<Xlsx.Cell>>()
        header(summary, "Timesheet", "${first.format(LONG_DATE)} – ${last.format(LONG_DATE)}")
        summary += listOf(Xlsx.Cell.Text("Date", S.HEADER), Xlsx.Cell.Text("Hours", S.HEADER_RIGHT))
        val headerRows = summary.size
        val weekTotalCells = mutableListOf<String>()
        for (w in weeks) {
            summary += listOf(
                Xlsx.Cell.Text("${weekLabel(w)}  ·  ${w.monday.format(SHORT_DATE)} – ${w.sunday.format(SHORT_DATE)}", S.SECTION),
                Xlsx.Cell.Text("", S.SECTION),
            )
            val firstDay = summary.size
            for (d in w.days) summary += listOf(Xlsx.Cell.Date(d.date, S.CELL_DATE), Xlsx.Cell.Hours(d.seconds, S.CELL_HOURS))
            val range = "${Xlsx.address(1, firstDay)}:${Xlsx.address(1, summary.size - 1)}"
            weekTotalCells += Xlsx.address(1, summary.size)
            summary += listOf(Xlsx.Cell.Text("Week ${w.week} total", S.TOTAL), Xlsx.Cell.HoursFormula("SUM($range)", w.seconds, S.TOTAL_HOURS))
            summary += emptyList<Xlsx.Cell>()
        }
        summary += listOf(
            Xlsx.Cell.Text("Total", S.TOTAL),
            Xlsx.Cell.HoursFormula(grandTotal(weekTotalCells), weeks.sumOf { it.seconds }, S.TOTAL_HOURS),
        )
        summary += emptyList<Xlsx.Cell>()
        summary += listOf(Xlsx.Cell.Text("Hours are hours:minutes:seconds worked, not counting breaks. Each week also has its own page.", S.NOTE))

        return listOf(Xlsx.Sheet("Timesheet", summary, widths = listOf(40, 14), freezeRows = headerRows, footer = footer)) + weekSheets
    }

    fun weekLabel(w: WeekHours): String = "Week ${w.week}, ${w.year}"

    /** "Week 47", or "Week 47 (2027)" when the export spans more than one year. */
    private fun weekSheetNames(weeks: List<WeekHours>): List<String> {
        val oneYear = weeks.map { it.year }.distinct().size == 1
        return weeks.map { if (oneYear) "Week ${it.week}" else "Week ${it.week} (${it.year})" }
    }

    private fun date(epochMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
}
