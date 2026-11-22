package com.sterbenden.clockedin.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ReportsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val h = 3_600_000L
    private val m = 60_000L

    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun session(id: String, start: String, end: String?, by: Actor = Actor.ME) =
        Session(id, at(start), end?.let { at(it) }, by, if (end == null) null else by)

    @Test
    fun `midnight crossing is split between days`() {
        val sessions = listOf(session("a", "2026-11-20T22:00", "2026-11-21T02:30"))
        val now = at("2026-11-22T12:00")
        assertEquals(2 * h, Reports.day(sessions, LocalDate.parse("2026-11-20"), zone, now).totalMs)
        assertEquals(2 * h + 30 * m, Reports.day(sessions, LocalDate.parse("2026-11-21"), zone, now).totalMs)
    }

    @Test
    fun `running session counts up to now`() {
        val sessions = listOf(session("a", "2026-11-22T09:00", null, Actor.AGENT))
        val now = at("2026-11-22T10:15")
        val today = Reports.day(sessions, LocalDate.parse("2026-11-22"), zone, now)
        assertEquals(h + 15 * m, today.totalMs)
        assertEquals(h + 15 * m, today.agentStartedMs)
        assertEquals(0, today.meStartedMs)
    }

    @Test
    fun `week runs Monday to Sunday and splits me and agent`() {
        val sessions = listOf(
            session("sun", "2026-11-15T10:00", "2026-11-15T11:00"), // previous week
            session("mon", "2026-11-16T09:00", "2026-11-16T12:00"),
            session("sun2", "2026-11-22T09:00", "2026-11-22T10:00", Actor.AGENT),
        )
        val week = Reports.week(sessions, LocalDate.parse("2026-11-19"), zone, at("2026-11-23T00:00"))
        assertEquals(4 * h, week.totalMs)
        assertEquals(3 * h, week.meStartedMs)
        assertEquals(h, week.agentStartedMs)
    }

    @Test
    fun `totals count unpaid time`() {
        val sessions = listOf(
            Session("a", at("2026-11-17T09:00"), at("2026-11-17T10:00"), Actor.ME, Actor.ME, paid = true),
            session("b", "2026-11-17T11:00", "2026-11-17T11:30"),
        )
        val day = Reports.day(sessions, LocalDate.parse("2026-11-17"), zone, at("2026-11-22T00:00"))
        assertEquals(h + 30 * m, day.totalMs)
        assertEquals(30 * m, day.unpaidMs)
    }

    @Test
    fun `daily report includes empty days`() {
        val sessions = listOf(session("a", "2026-11-17T09:00", "2026-11-17T10:00"))
        val report = Reports.report(
            sessions, LocalDate.parse("2026-11-16"), LocalDate.parse("2026-11-18"), GroupBy.DAY, zone, at("2026-11-22T00:00"),
        )
        assertEquals(listOf(0L, h, 0L), report.map { it.totals.totalMs })
    }

    @Test
    fun `weekly report clips the first and last week to the range`() {
        val sessions = listOf(
            session("a", "2026-11-12T09:00", "2026-11-12T10:00"), // Thu, week 1
            session("b", "2026-11-18T09:00", "2026-11-18T11:00"), // Wed, week 2
        )
        val report = Reports.report(
            sessions, LocalDate.parse("2026-11-11"), LocalDate.parse("2026-11-18"), GroupBy.WEEK, zone, at("2026-11-22T00:00"),
        )
        assertEquals(2, report.size)
        assertEquals(LocalDate.parse("2026-11-11"), report[0].start)
        assertEquals(LocalDate.parse("2026-11-15"), report[0].endInclusive)
        assertEquals(h, report[0].totals.totalMs)
        assertEquals(LocalDate.parse("2026-11-16"), report[1].start)
        assertEquals(LocalDate.parse("2026-11-18"), report[1].endInclusive)
        assertEquals(2 * h, report[1].totals.totalMs)
    }

    @Test
    fun `duration formatting`() {
        assertEquals("0m", Formats.duration(0))
        assertEquals("45m", Formats.duration(45 * m + 59_000))
        assertEquals("3h 05m", Formats.duration(3 * h + 5 * m))
    }

    @Test
    fun `clock and agent name formatting`() {
        assertEquals("00:00:00", Formats.clock(0))
        assertEquals("03:12:45", Formats.clock(3 * h + 12 * m + 45_999))
        assertEquals("26:00:00", Formats.clock(26 * h))
        assertEquals("Claude Code", Formats.agentName("claude-code"))
        assertEquals("GitHub Copilot", Formats.agentName("GitHub Copilot"))
        assertEquals(null, Formats.agentName(" "))
    }

    @Test
    fun `timesheet lists days, totals and the split`() {
        val sessions = listOf(
            session("a", "2026-11-17T09:00", "2026-11-17T10:00"),
            Session("b", at("2026-11-17T11:00"), at("2026-11-17T11:30"), Actor.AGENT, Actor.AGENT, startedByAgent = "claude-code"),
        )
        val html = Timesheet.html(sessions, LocalDate.parse("2026-11-16"), LocalDate.parse("2026-11-18"), zone, at("2026-11-22T00:00"))
        assertTrue(html.contains("2026-11-17 total"))
        assertTrue(html.contains("1h 30m"))
        assertTrue(html.contains("Claude Code"))
        assertTrue(html.contains("(you 1h 00m, agents 30m)"))
        val empty = Timesheet.html(emptyList(), LocalDate.parse("2026-11-16"), LocalDate.parse("2026-11-16"), zone, at("2026-11-22T00:00"))
        assertTrue(empty.contains("No sessions in this period."))
        assertTrue(html.startsWith("<!DOCTYPE html>") && !html.contains("window.print"))
        val printable = Timesheet.html(sessions, LocalDate.parse("2026-11-16"), LocalDate.parse("2026-11-18"), zone, at("2026-11-22T00:00"), autoPrint = true)
        assertTrue(printable.contains("window.print()"))
        val out = java.nio.file.Path.of(System.getProperty("user.dir"), "build", "ui-snapshots")
        java.nio.file.Files.createDirectories(out)
        java.nio.file.Files.writeString(out.resolve("timesheet-sample.html"), html)
    }

    @Test
    fun `date and time parsing`() {
        assertEquals(at("2026-11-22T09:30"), Formats.parseDateTime(" 2026-11-22 09:30 ", zone))
        assertEquals(null, Formats.parseDateTime("2026-11-22 25:00", zone))
        assertEquals(null, Formats.parseDate("22.11.2026"))
    }
}
