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
    fun `timesheet lists days with weekly totals, then a page per week, as a valid workbook`() {
        val weeks = listOf(
            WeekHours(
                LocalDate.parse("2026-11-16"),
                listOf(DayHours(LocalDate.parse("2026-11-17"), 7 * hour + 1_800), DayHours(LocalDate.parse("2026-11-18"), 23 * hour)),
            ),
            WeekHours(LocalDate.parse("2026-11-23"), listOf(DayHours(LocalDate.parse("2026-11-23"), 8 * hour))),
        )
        val sheets = UnpaidHours.workbook(weeks, LocalDate.parse("2026-11-24"), "Sam Example")
        assertEquals(listOf("Timesheet", "Week 47", "Week 48"), sheets.map { it.name })

        val bytes = Xlsx.write(sheets)
        val parts = mutableMapOf<String, String>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
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
        for ((_, xml) in parts) factory.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xml)))

        // Every style a cell uses exists in styles.xml.
        val styles = parts.getValue("xl/styles.xml")
        val xfCount = Regex("""<cellXfs count="(\d+)">""").find(styles)!!.groupValues[1].toInt()
        assertEquals(Xlsx.Style.entries.size, xfCount)
        assertEquals(xfCount, Regex("<cellXfs.*?</cellXfs>").find(styles)!!.value.split("<xf ").size - 1)
        for (count in listOf("fonts", "fills", "borders", "numFmts")) {
            val declared = Regex("""<$count count="(\d+)">""").find(styles)!!.groupValues[1].toInt()
            val tag = count.removeSuffix("s").let { if (it == "numFmt") "numFmt" else it }
            assertEquals(count, declared, Regex("<$tag[ >]").findAll(Regex("<$count .*?</$count>").find(styles)!!.value).count())
        }

        val main = parts.getValue("xl/worksheets/sheet1.xml")
        assertTrue(main, main.contains(">Timesheet<") && main.contains(">Sam Example<"))
        assertTrue(main, main.contains(">Week 47, 2026  ·  16 Nov 2026 – 22 Nov 2026<"))
        assertTrue(main, main.contains(">Week 47 total<") && main.contains(">Week 48 total<") && main.contains(">Total<"))
        // Week 47's days (rows 8-9) are summed under them, and the grand total adds the week totals.
        assertTrue(main, main.contains("<f>SUM(B8:B9)</f>"))
        assertTrue(main, main.contains("<f>SUM(B10,B14)</f>"))
        // 38.5 hours as a fraction of a day: [h]:mm:ss shows it as 38:30:00, not 14:30:00.
        assertTrue(main, main.contains("<v>1.604166666666667</v>"))
        assertTrue(styles.contains("formatCode=\"[h]:mm:ss\""))
        assertTrue(main, main.contains("""<pane ySplit="6""""))

        val week = parts.getValue("xl/worksheets/sheet2.xml")
        assertTrue(week, week.contains(">Week 47, 2026<") && week.contains(">Week total<"))
        assertTrue(week, week.contains("<f>SUM(B7:B8)</f>"))
        assertTrue(week, week.contains("<v>46343</v>")) // 2026-11-17 as a spreadsheet date
        assertTrue(week, week.contains("fitToWidth=\"1\""))

        val out = java.nio.file.Path.of(System.getProperty("user.dir"), "build", "ui-snapshots")
        java.nio.file.Files.createDirectories(out)
        java.nio.file.Files.write(out.resolve("timesheet-sample.xlsx"), bytes)
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
