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

class CsvAndStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone = ZoneId.of("UTC")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `csv splits at midnight and marks running sessions`() {
        val sessions = listOf(
            Session("a", at("2026-11-20T23:00"), at("2026-11-21T01:00"), Actor.AGENT, Actor.ME, startedByAgent = "Claude, Code"),
            Session("b", at("2026-11-21T09:00"), null, Actor.ME),
        )
        val csv = CsvExport.export(sessions, LocalDate.parse("2026-11-20"), LocalDate.parse("2026-11-21"), zone, at("2026-11-21T09:30"))
        val lines = csv.trimEnd().lines()
        assertEquals(CsvExport.HEADER, lines[0])
        assertEquals("2026-11-20,23:00,00:00,0,60,1.00,agent,me,\"Claude, Code\",no", lines[1])
        assertEquals("2026-11-21,00:00,01:00,0,60,1.00,agent,me,\"Claude, Code\",no", lines[2])
        assertEquals("2026-11-21,09:00,09:30,0,30,0.50,me,running,,no", lines[3])
        assertEquals(4, lines.size)
    }

    @Test
    fun `csv only includes the requested days`() {
        val sessions = listOf(Session("a", at("2026-11-20T23:00"), at("2026-11-21T01:00"), Actor.ME, Actor.ME))
        val csv = CsvExport.export(sessions, LocalDate.parse("2026-11-21"), LocalDate.parse("2026-11-21"), zone, at("2026-11-22T00:00"))
        assertEquals(listOf(CsvExport.HEADER, "2026-11-21,00:00,01:00,0,60,1.00,me,me,,no"), csv.trimEnd().lines())
    }

    @Test
    fun `csv has a paid column and filters by paid status`() {
        val sessions = listOf(
            Session("a", at("2026-11-21T09:00"), at("2026-11-21T10:00"), Actor.ME, Actor.ME, paid = true),
            Session("b", at("2026-11-21T11:00"), at("2026-11-21T12:00"), Actor.ME, Actor.ME),
        )
        val day = LocalDate.parse("2026-11-21")
        val now = at("2026-11-22T00:00")
        assertTrue(CsvExport.HEADER.endsWith(",paid"))
        val all = CsvExport.export(sessions, day, day, zone, now).trimEnd().lines()
        assertEquals(listOf("yes", "no"), all.drop(1).map { it.substringAfterLast(',') })
        val unpaid = CsvExport.export(sessions, day, day, zone, now, PaidFilter.UNPAID).trimEnd().lines()
        assertEquals(listOf("2026-11-21,11:00,12:00,0,60,1.00,me,me,,no"), unpaid.drop(1))
        val paid = CsvExport.export(sessions, day, day, zone, now, PaidFilter.PAID).trimEnd().lines()
        assertEquals(listOf("2026-11-21,09:00,10:00,0,60,1.00,me,me,,yes"), paid.drop(1))
    }

    @Test
    fun `csv subtracts breaks and shows them`() {
        val s = Session(
            "a", at("2026-11-21T09:00"), at("2026-11-21T12:00"), Actor.ME, Actor.ME,
            breaks = listOf(Break(at("2026-11-21T10:00"), at("2026-11-21T10:30"), Actor.ME)),
        )
        val day = LocalDate.parse("2026-11-21")
        val lines = CsvExport.export(listOf(s), day, day, zone, at("2026-11-22T00:00")).trimEnd().lines()
        assertEquals("2026-11-21,09:00,12:00,30,150,2.50,me,me,,no", lines[1])
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
