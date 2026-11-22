package com.sterbenden.clockedin.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ExportAndStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone = ZoneId.of("UTC")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private val hour = 3_600L

    @Test
    fun `unpaid hours are per day, grouped by week, and skip paid sessions`() {
        val sessions = listOf(
            Session("paid", at("2026-11-16T09:00"), at("2026-11-16T17:00"), Actor.ME, Actor.ME, paid = true),
            Session("a", at("2026-11-17T09:00"), at("2026-11-17T12:00"), Actor.ME, Actor.ME),
            Session("b", at("2026-11-17T13:00"), at("2026-11-17T15:00"), Actor.AGENT, Actor.AGENT, startedByAgent = "claude-code"),
            Session(
                "c", at("2026-11-22T22:00"), at("2026-11-23T02:00"), Actor.ME, Actor.ME,
                breaks = listOf(Break(at("2026-11-23T00:30"), at("2026-11-23T01:00"), Actor.ME)),
            ),
            Session("open", at("2026-11-24T09:00"), null, Actor.ME),
        )
        val weeks = UnpaidHours.weeks(sessions, zone, at("2026-11-24T10:15"))
        assertEquals(listOf(LocalDate.parse("2026-11-16"), LocalDate.parse("2026-11-23")), weeks.map { it.monday })
        assertEquals(
            listOf(DayHours(LocalDate.parse("2026-11-17"), 5 * hour), DayHours(LocalDate.parse("2026-11-22"), 2 * hour)),
            weeks[0].days,
        )
        assertEquals(
            listOf(DayHours(LocalDate.parse("2026-11-23"), 3 * hour / 2), DayHours(LocalDate.parse("2026-11-24"), 5 * hour / 4)),
            weeks[1].days,
        )
        assertEquals(7 * hour, weeks[0].seconds)
        assertEquals("Week 47, 2026", UnpaidHours.weekLabel(weeks[0]))
    }

    @Test
    fun `nothing unpaid means nothing to export`() {
        val sessions = listOf(Session("a", at("2026-11-17T09:00"), at("2026-11-17T12:00"), Actor.ME, Actor.ME, paid = true))
        assertTrue(UnpaidHours.weeks(sessions, zone, at("2026-11-18T00:00")).isEmpty())
    }

    @Test
    fun `workbook has a summary and one sheet per week with well formed parts`() {
        val weeks = listOf(
            WeekHours(LocalDate.parse("2026-11-16"), listOf(DayHours(LocalDate.parse("2026-11-17"), 30 * hour))),
            WeekHours(LocalDate.parse("2026-11-23"), listOf(DayHours(LocalDate.parse("2026-11-23"), 8 * hour))),
        )
        val sheets = UnpaidHours.workbook(weeks, LocalDate.parse("2026-11-24"))
        assertEquals(listOf("Summary", "2026-W47", "2026-W48"), sheets.map { it.name })

        val parts = mutableMapOf<String, String>()
        java.util.zip.ZipInputStream(Xlsx.write(sheets).inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(
            setOf(
                "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml",
                "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/worksheets/sheet3.xml",
            ),
            parts.keys,
        )
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for ((name, xml) in parts) {
            factory.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xml))) // throws if malformed
            assertTrue(name, xml.startsWith("<?xml"))
        }
        val summary = parts.getValue("xl/worksheets/sheet1.xml")
        assertTrue(summary, summary.contains(">Week 47, 2026<") && summary.contains(">Total<"))
        // 38 hours as a fraction of a day, shown with the [h]:mm:ss format so it reads 38:00:00.
        assertTrue(summary, summary.contains("<v>1.583333333333333</v>"))
        assertTrue(parts.getValue("xl/styles.xml").contains("formatCode=\"[h]:mm:ss\""))
        val week = parts.getValue("xl/worksheets/sheet2.xml")
        assertTrue(week, week.contains(">2026-11-17<") && week.contains(">Tuesday<") && week.contains(">Week total<"))
        assertTrue(week, week.contains("<v>1.25</v>"))
    }

    @Test
    fun `sheet names follow Excel's rules`() {
        assertEquals("a-b-c", Xlsx.sheetName("a/b:c"))
        assertEquals(31, Xlsx.sheetName("x".repeat(40)).length)
    }

    @Test
    fun `files saved before the paid flag existed load as unpaid`() {
        val file = tmp.root.toPath().resolve("old.json")
        Files.writeString(file, """{"version":1,"sessions":[{"id":"a","start":1,"end":2,"startedBy":"ME","stoppedBy":"ME"}]}""")
        assertEquals(false, SessionStore(file).load().sessions.single().paid)
    }

    @Test
    fun `store round-trips data`() {
        val file = tmp.root.toPath().resolve("clocked-in/sessions.json")
        val store = SessionStore(file)
        assertTrue(store.load().sessions.isEmpty())
        val data = LedgerData(sessions = listOf(Session("a", 1, null, Actor.AGENT, startedByAgent = "Claude Code")), heartbeat = 5)
        store.save(data)
        assertEquals(data, SessionStore(file).load())
    }

    @Test
    fun `store moves a corrupt file aside and starts empty`() {
        val file = tmp.root.toPath().resolve("sessions.json")
        Files.writeString(file, "{ not json")
        val store = SessionStore(file)
        assertTrue(store.load().sessions.isEmpty())
        assertNotNull(store.lastLoadProblem)
        assertTrue(Files.list(tmp.root.toPath()).anyMatch { it.fileName.toString().startsWith("sessions.json.corrupt-") })
        assertNull(SessionStore(file).also { it.load() }.lastLoadProblem)
    }
}
