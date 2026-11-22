package com.sterbenden.clockedin.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockLedgerTest {
    private var counter = 0
    private fun ledger(data: LedgerData = LedgerData()) = ClockLedger(data) { "id${counter++}" }

    private val min = 60_000L

    @Test
    fun `clock in then out records one finished session`() {
        val l = ledger()
        val started = l.clockIn(Actor.ME, 1_000) as ClockInResult.Started
        assertEquals(1_000, started.session.start)
        val stopped = l.clockOut(Actor.ME, 1_000 + 30 * min) as ClockOutResult.Stopped
        assertEquals(30 * min, stopped.session!!.durationMs(0))
        assertNull(l.open)
        assertEquals(1, l.sessions.size)
    }

    @Test
    fun `clocking in while running changes nothing`() {
        val l = ledger()
        l.clockIn(Actor.ME, 0)
        val again = l.clockIn(Actor.AGENT, 10 * min, "Claude Code")
        assertTrue(again is ClockInResult.AlreadyRunning)
        assertEquals(1, l.sessions.size)
        assertEquals(Actor.ME, l.open!!.startedBy)
    }

    @Test
    fun `clocking out when stopped changes nothing`() {
        assertEquals(ClockOutResult.NotRunning, ledger().clockOut(Actor.AGENT, 0))
    }

    @Test
    fun `agent can't stop a session I started`() {
        val l = ledger()
        l.clockIn(Actor.ME, 0)
        assertTrue(l.clockOut(Actor.AGENT, 5 * min) is ClockOutResult.LeftRunning)
        assertTrue(l.open != null)
        assertTrue(l.clockOut(Actor.ME, 6 * min) is ClockOutResult.Stopped)
    }

    @Test
    fun `agent can stop a session an agent started and names are recorded`() {
        val l = ledger()
        l.clockIn(Actor.AGENT, 0, "Claude Code")
        val stopped = l.clockOut(Actor.AGENT, 5 * min, "Junie") as ClockOutResult.Stopped
        assertEquals("Claude Code", stopped.session!!.startedByAgent)
        assertEquals("Junie", stopped.session!!.stoppedByAgent)
        assertEquals(Actor.AGENT, stopped.session!!.stoppedBy)
    }

    @Test
    fun `I can stop a session an agent started`() {
        val l = ledger()
        l.clockIn(Actor.AGENT, 0, "Claude Code")
        val stopped = l.clockOut(Actor.ME, 5 * min) as ClockOutResult.Stopped
        assertEquals(Actor.ME, stopped.session!!.stoppedBy)
        assertNull(stopped.session!!.stoppedByAgent)
    }

    @Test
    fun `new session never starts before the previous one ended`() {
        val l = ledger()
        l.clockIn(Actor.ME, 100 * min)
        l.clockOut(Actor.ME, 120 * min)
        val started = l.clockIn(Actor.ME, 110 * min) as ClockInResult.Started // system clock moved back
        assertEquals(120 * min, started.session.start)
    }

    @Test
    fun `crash recovery closes the running session at the last heartbeat`() {
        val l = ledger()
        l.clockIn(Actor.AGENT, 0, "Claude Code")
        l.beat(7 * min)
        val reloaded = ledger(l.snapshot())
        val closed = reloaded.recoverAfterCrash()!!
        assertEquals(7 * min, closed.end)
        assertEquals(Actor.SYSTEM, closed.stoppedBy)
        assertNull(reloaded.open)
        assertNull(reloaded.recoverAfterCrash())
    }

    @Test
    fun `reloading the plugin in the same IDE process keeps the session open`() {
        val l = ledger()
        l.clockIn(Actor.ME, 10 * min)
        l.beat(70 * min)
        val reloaded = ledger(l.snapshot())
        assertNull(reloaded.recoverAfterCrash(processStart = 5 * min))
        assertTrue(reloaded.open!!.isRunning)
    }

    @Test
    fun `reloading the plugin keeps a break that began in the same IDE process`() {
        val l = ledger()
        l.clockIn(Actor.ME, 0)
        l.pause(Actor.ME, 30 * min)
        val reloaded = ledger(l.snapshot())
        assertNull(reloaded.recoverAfterCrash(processStart = 20 * min))
        assertEquals(30 * min, reloaded.open!!.openBreak!!.start)
    }

    @Test
    fun `a session last active before the IDE started is recovered`() {
        val l = ledger()
        l.clockIn(Actor.ME, 0)
        l.beat(7 * min)
        val reloaded = ledger(l.snapshot())
        assertEquals(7 * min, reloaded.recoverAfterCrash(processStart = 60 * min)!!.end)
    }

    @Test
    fun `beat does nothing when stopped`() {
        val l = ledger()
        assertEquals(false, l.beat(5))
        assertNull(l.heartbeat)
    }

    @Test
    fun `undo clock in removes the session`() {
        val l = ledger()
        val started = l.clockIn(Actor.AGENT, 0) as ClockInResult.Started
        assertEquals(UndoResult.Undone, l.undo(started.event))
        assertTrue(l.sessions.isEmpty())
        assertTrue(l.undo(started.event) is UndoResult.NotPossible)
    }

    @Test
    fun `undo clock out reopens the session`() {
        val l = ledger()
        l.clockIn(Actor.AGENT, 0)
        val stopped = l.clockOut(Actor.AGENT, 5 * min) as ClockOutResult.Stopped
        assertEquals(UndoResult.Undone, l.undo(stopped.event))
        assertTrue(l.open != null)
        assertNull(l.open!!.stoppedBy)
    }

    @Test
    fun `undo clock out is refused once the clock runs again`() {
        val l = ledger()
        l.clockIn(Actor.AGENT, 0)
        val stopped = l.clockOut(Actor.AGENT, 5 * min) as ClockOutResult.Stopped
        l.clockIn(Actor.ME, 6 * min)
        assertTrue(l.undo(stopped.event) is UndoResult.NotPossible)
    }

    @Test
    fun `undo clock out is refused when a newer session exists`() {
        val l = ledger()
        l.clockIn(Actor.AGENT, 0)
        val stopped = l.clockOut(Actor.AGENT, 5 * min) as ClockOutResult.Stopped
        l.clockIn(Actor.ME, 6 * min)
        l.clockOut(Actor.ME, 7 * min)
        assertTrue(l.undo(stopped.event) is UndoResult.NotPossible)
    }

    @Test
    fun `add and edit reject overlaps and bad times`() {
        val l = ledger()
        assertTrue(l.add(0, 10 * min, now = 100 * min) is EditResult.Ok)
        assertTrue(l.add(5 * min, 15 * min, now = 100 * min) is EditResult.Invalid)
        assertTrue(l.add(20 * min, 15 * min, now = 100 * min) is EditResult.Invalid)
        assertTrue(l.add(90 * min, 110 * min, now = 100 * min) is EditResult.Invalid)
        val second = l.add(20 * min, 30 * min, now = 100 * min) as EditResult.Ok
        assertTrue(l.edit(second.session.id, 8 * min, 30 * min, now = 100 * min) is EditResult.Invalid)
        assertTrue(l.edit(second.session.id, 10 * min, 30 * min, now = 100 * min) is EditResult.Ok)
    }

    @Test
    fun `running session can be edited only without an end`() {
        val l = ledger()
        val s = (l.clockIn(Actor.ME, 50 * min) as ClockInResult.Started).session
        assertTrue(l.edit(s.id, 40 * min, 60 * min, now = 100 * min) is EditResult.Invalid)
        val edited = l.edit(s.id, 40 * min, null, now = 100 * min) as EditResult.Ok
        assertEquals(40 * min, edited.session.start)
        assertTrue(edited.session.isRunning)
    }

    @Test
    fun `added session can't overlap the running one`() {
        val l = ledger()
        l.clockIn(Actor.ME, 50 * min)
        assertTrue(l.add(60 * min, 70 * min, now = 100 * min) is EditResult.Invalid)
        assertTrue(l.add(10 * min, 20 * min, now = 100 * min) is EditResult.Ok)
    }

    @Test
    fun `delete removes a session`() {
        val l = ledger()
        val s = (l.clockIn(Actor.ME, 0) as ClockInResult.Started).session
        assertTrue(l.delete(s.id))
        assertNull(l.open)
        assertEquals(false, l.delete(s.id))
    }

    @Test
    fun `loading data with two running sessions keeps only the latest running`() {
        val data = LedgerData(
            sessions = listOf(
                Session("a", start = 0, startedBy = Actor.ME),
                Session("b", start = 10 * min, startedBy = Actor.AGENT),
            ),
        )
        val l = ledger(data)
        assertEquals("b", l.open!!.id)
        assertEquals(10 * min, l.sessions.first { it.id == "a" }.end)
    }

    // --- paid ---

    @Test
    fun `sessions can be marked paid and unpaid`() {
        val l = ledger()
        val a = (l.add(0, 10 * min, now = 100 * min) as EditResult.Ok).session
        val b = (l.add(20 * min, 30 * min, now = 100 * min) as EditResult.Ok).session
        assertEquals(2, l.setPaid(listOf(a.id, b.id), true))
        assertEquals(0, l.setPaid(listOf(a.id), true))
        assertTrue(l.sessions.all { it.paid })
        assertEquals(1, l.setPaid(listOf(b.id), false))
        assertEquals(listOf(true, false), l.sessions.map { it.paid })
        assertTrue(ledger(l.snapshot()).sessions.first().paid)
    }

    @Test
    fun `editing times keeps the paid flag`() {
        val l = ledger()
        val a = (l.add(0, 10 * min, now = 100 * min) as EditResult.Ok).session
        l.setPaid(listOf(a.id), true)
        val edited = l.edit(a.id, 0, 15 * min, now = 100 * min) as EditResult.Ok
        assertTrue(edited.session.paid)
    }

    // --- breaks ---

    @Test
    fun `pause and resume keep the same session and breaks don't count as work`() {
        val l = ledger()
        val id = (l.clockIn(Actor.ME, 0) as ClockInResult.Started).session.id
        val paused = l.pause(Actor.ME, 10 * min) as PauseResult.Paused
        assertNull(paused.session.end)
        assertEquals(10 * min, paused.session.openBreak!!.start)
        assertEquals(ClockState.PAUSED, l.state)
        assertTrue(l.pause(Actor.ME, 11 * min) is PauseResult.AlreadyPaused)

        val resumed = l.clockIn(Actor.ME, 15 * min) as ClockInResult.Started
        assertTrue(resumed.resumed)
        assertEquals(id, resumed.session.id)
        assertEquals(ClockState.RUNNING, l.state)
        assertEquals(1, l.sessions.size)

        val stopped = (l.clockOut(Actor.ME, 30 * min) as ClockOutResult.Stopped).session
        assertEquals(30 * min, stopped.end)
        assertEquals(5 * min, stopped.breakMs(0))
        assertEquals(25 * min, stopped.durationMs(0))
        assertEquals(ClockState.STOPPED, l.state)

        val next = l.clockIn(Actor.ME, 40 * min) as ClockInResult.Started
        assertFalse(next.resumed)
        assertEquals(2, l.sessions.size)
    }

    @Test
    fun `worked time stays still during a break`() {
        val l = ledger()
        l.clockIn(Actor.ME, 0)
        l.pause(Actor.ME, 10 * min)
        assertEquals(10 * min, l.open!!.durationMs(10 * min))
        assertEquals(10 * min, l.open!!.durationMs(60 * min))
        assertEquals(50 * min, l.open!!.breakMs(60 * min))
    }

    @Test
    fun `stopping during a break ends the session when the break began`() {
        val l = ledger()
        l.clockIn(Actor.ME, 0)
        l.pause(Actor.ME, 5 * min)
        assertTrue(l.clockOut(Actor.AGENT, 6 * min) is ClockOutResult.LeftOnBreak)
        val stopped = (l.clockOut(Actor.ME, 20 * min) as ClockOutResult.Stopped).session
        assertEquals(5 * min, stopped.end)
        assertTrue(stopped.breaks.isEmpty())
        assertEquals(ClockState.STOPPED, l.state)
    }

    @Test
    fun `pause when clocked out does nothing`() {
        assertEquals(PauseResult.NotRunning, ledger().pause(Actor.AGENT, 0))
    }

    @Test
    fun `agent can't pause a session I started`() {
        val l = ledger()
        l.clockIn(Actor.ME, 0)
        assertTrue(l.pause(Actor.AGENT, 5 * min) is PauseResult.LeftRunning)
        assertEquals(ClockState.RUNNING, l.state)
    }

    @Test
    fun `agent resumes its own break but not mine`() {
        val l = ledger()
        l.clockIn(Actor.AGENT, 0, "claude-code")
        l.pause(Actor.AGENT, 5 * min, "claude-code")
        assertTrue((l.clockIn(Actor.AGENT, 6 * min, "claude-code") as ClockInResult.Started).resumed)

        l.pause(Actor.AGENT, 7 * min, "claude-code")
        l.clockIn(Actor.ME, 8 * min)
        l.pause(Actor.ME, 9 * min)
        assertTrue(l.clockIn(Actor.AGENT, 10 * min, "claude-code") is ClockInResult.OnBreak)
        assertEquals(ClockState.PAUSED, l.state)
        assertEquals(1, l.sessions.size)
    }

    @Test
    fun `undo pause, resume and stop`() {
        val l = ledger()
        l.clockIn(Actor.AGENT, 0)
        val paused = l.pause(Actor.AGENT, 5 * min) as PauseResult.Paused
        assertEquals(UndoResult.Undone, l.undo(paused.event))
        assertEquals(ClockState.RUNNING, l.state)
        assertTrue(l.open!!.breaks.isEmpty())

        l.pause(Actor.AGENT, 6 * min)
        val resumed = l.clockIn(Actor.AGENT, 8 * min) as ClockInResult.Started
        assertEquals(UndoResult.Undone, l.undo(resumed.event))
        assertEquals(ClockState.PAUSED, l.state)
        assertEquals(6 * min, l.open!!.openBreak!!.start)

        val stopped = l.clockOut(Actor.AGENT, 9 * min) as ClockOutResult.Stopped
        assertEquals(UndoResult.Undone, l.undo(stopped.event))
        assertEquals(ClockState.PAUSED, l.state)
    }

    @Test
    fun `crash during a break ends the session when the break began`() {
        val l = ledger()
        l.clockIn(Actor.ME, 0)
        l.beat(4 * min)
        l.pause(Actor.ME, 5 * min)
        val reloaded = ledger(l.snapshot())
        assertEquals(ClockState.PAUSED, reloaded.state)
        assertEquals(5 * min, reloaded.recoverAfterCrash()!!.end)
    }

    @Test
    fun `a break saved by the old version is turned back into an open session`() {
        val old = LedgerData(
            sessions = listOf(Session("a", 0, 10 * min, Actor.ME, Actor.ME)),
            pause = Pause(Actor.ME, null, 10 * min),
        )
        val l = ledger(old)
        assertEquals(ClockState.PAUSED, l.state)
        assertEquals(10 * min, l.open!!.openBreak!!.start)
        val resumed = l.clockIn(Actor.ME, 15 * min) as ClockInResult.Started
        assertEquals("a", resumed.session.id)
        assertNull(l.snapshot().pause)
    }

    @Test
    fun `editing can't move a session past its breaks`() {
        val l = ledger()
        val id = (l.clockIn(Actor.ME, 0) as ClockInResult.Started).session.id
        l.pause(Actor.ME, 10 * min)
        l.clockIn(Actor.ME, 20 * min)
        l.clockOut(Actor.ME, 30 * min)
        assertTrue(l.edit(id, 0, 15 * min, now = 60 * min) is EditResult.Invalid)
        assertTrue(l.edit(id, 0, 40 * min, now = 60 * min) is EditResult.Ok)
    }
}
