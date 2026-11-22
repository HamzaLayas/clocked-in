package com.sterbenden.clockedin.mcp

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpCallInfoOrNull
import com.sterbenden.clockedin.core.Actor
import com.sterbenden.clockedin.core.ClockInResult
import com.sterbenden.clockedin.core.ClockOutResult
import com.sterbenden.clockedin.core.Formats
import com.sterbenden.clockedin.core.GroupBy
import com.sterbenden.clockedin.core.PauseResult
import com.sterbenden.clockedin.core.Reports
import com.sterbenden.clockedin.core.Totals
import com.sterbenden.clockedin.service.ClockNotifier
import com.sterbenden.clockedin.service.ClockService
import com.sterbenden.clockedin.service.ClockStatus
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class TotalsDto(
    val minutes: Long,
    val formatted: String,
    val startedByMeMinutes: Long,
    val startedByAgentMinutes: Long,
)

@Serializable
data class ClockStatusDto(
    /** `running`, `paused` (on a break) or `stopped` (clocked out). */
    val state: String,
    val running: Boolean,
    val runningSince: String? = null,
    val startedBy: String? = null,
    val startedByAgent: String? = null,
    val elapsedMinutes: Long? = null,
    val pausedSince: String? = null,
    val pausedBy: String? = null,
    val today: TotalsDto,
    val thisWeek: TotalsDto,
)

@Serializable
data class ClockInResponse(
    /** `started`, `resumed`, `already_running` or `on_break_by_me`. */
    val outcome: String,
    val message: String,
    val status: ClockStatusDto,
)

@Serializable
data class ClockPauseResponse(
    /** `paused`, `already_paused`, `not_running` or `left_running_started_by_me`. */
    val outcome: String,
    val message: String,
    val sessionMinutes: Long? = null,
    val status: ClockStatusDto,
)

@Serializable
data class ClockOutResponse(
    /** `stopped`, `not_running`, `left_running_started_by_me` or `left_on_break_by_me`. */
    val outcome: String,
    val message: String,
    val sessionMinutes: Long? = null,
    val status: ClockStatusDto,
)

@Serializable
data class PeriodDto(val from: String, val to: String, val totals: TotalsDto)

@Serializable
data class ClockReportResponse(
    val from: String,
    val to: String,
    val groupBy: String,
    val periods: List<PeriodDto>,
    val total: TotalsDto,
)

/**
 * MCP tools for the shared work clock. Agents act as [Actor.AGENT]; each start, pause or
 * stop shows the developer a notification with Undo.
 */
class ClockToolset : McpToolset {

    @McpTool(name = "clock_in")
    @McpDescription(
        description = """
        |Clocked In: start the developer's work clock, or resume it after a break you took.
        |Use only when the user asked you to track working time, for example by running the clocked-in skill.
        |Call it before starting work on each request.
        |Outcomes: `started`, `resumed`, `already_running` (nothing changed, not an error) and
        |`on_break_by_me` (the developer is on a break they took themselves; the clock stays paused,
        |so carry on with the request without retrying).
        """,
    )
    suspend fun clock_in(): ClockInResponse {
        val agent = callerName()
        val service = ClockService.getInstance()
        return when (val result = service.clockIn(Actor.AGENT, agent)) {
            is ClockInResult.Started -> {
                ClockNotifier.agentClockedIn(agent, result.event, result.resumed)
                val outcome = if (result.resumed) "resumed" else "started"
                ClockInResponse(outcome, if (result.resumed) "Resumed after a break." else "Clocked in.", statusDto(service.status()))
            }
            is ClockInResult.AlreadyRunning -> ClockInResponse(
                "already_running",
                "The clock was already running; nothing changed.",
                statusDto(service.status()),
            )
            is ClockInResult.OnBreak -> ClockInResponse(
                "on_break_by_me",
                "The developer is on a break they took themselves, so the clock stays paused.",
                statusDto(service.status()),
            )
        }
    }

    @McpTool(name = "clock_pause")
    @McpDescription(
        description = """
        |Clocked In: pause the developer's work clock (a break inside the current session). The next
        |`clock_in` resumes the same session.
        |Use only when the user asked you to track working time. Call it when you finish a request,
        |or before you stop to wait for the user's answer.
        |Outcomes: `paused`, `already_paused`, `not_running`, and `left_running_started_by_me` (the
        |developer clocked in themselves, so the clock keeps running; that is expected, don't retry).
        """,
    )
    suspend fun clock_pause(): ClockPauseResponse {
        val agent = callerName()
        val service = ClockService.getInstance()
        return when (val result = service.pause(Actor.AGENT, agent)) {
            is PauseResult.Paused -> {
                val worked = result.session.durationMs(result.event.at)
                ClockNotifier.agentPaused(agent, result.event, worked)
                ClockPauseResponse(
                    "paused",
                    "Paused after ${Formats.duration(worked)}.",
                    Formats.minutes(worked),
                    statusDto(service.status()),
                )
            }
            is PauseResult.AlreadyPaused -> ClockPauseResponse(
                "already_paused",
                "The clock was already paused; nothing changed.",
                status = statusDto(service.status()),
            )
            PauseResult.NotRunning -> ClockPauseResponse(
                "not_running",
                "The developer is clocked out; nothing changed.",
                status = statusDto(service.status()),
            )
            is PauseResult.LeftRunning -> ClockPauseResponse(
                "left_running_started_by_me",
                "The developer started this session, so it keeps running.",
                status = statusDto(service.status()),
            )
        }
    }

    @McpTool(name = "clock_out")
    @McpDescription(
        description = """
        |Clocked In: clock the developer out, ending the current session (running or on a break).
        |The next `clock_in` starts a new session.
        |Only call it when the user explicitly asks you to stop the clock or clock out. At the end of a
        |request, use `clock_pause` instead.
        |Outcomes: `stopped`, `not_running`, `left_running_started_by_me` and `left_on_break_by_me`
        |(the developer started the session or took the break themselves, so nothing changed).
        """,
    )
    suspend fun clock_out(): ClockOutResponse {
        val agent = callerName()
        val service = ClockService.getInstance()
        return when (val result = service.clockOut(Actor.AGENT, agent)) {
            is ClockOutResult.Stopped -> {
                val worked = result.session.durationMs(result.event.at)
                ClockNotifier.agentClockedOut(agent, result.event, worked)
                ClockOutResponse(
                    "stopped",
                    "Clocked out after ${Formats.duration(worked)} of work.",
                    Formats.minutes(worked),
                    statusDto(service.status()),
                )
            }
            ClockOutResult.NotRunning -> ClockOutResponse(
                "not_running",
                "The developer was already clocked out; nothing changed.",
                status = statusDto(service.status()),
            )
            is ClockOutResult.LeftRunning -> ClockOutResponse(
                "left_running_started_by_me",
                "The developer started this session, so it keeps running.",
                status = statusDto(service.status()),
            )
            is ClockOutResult.LeftOnBreak -> ClockOutResponse(
                "left_on_break_by_me",
                "The developer is on a break they took themselves; nothing changed.",
                status = statusDto(service.status()),
            )
        }
    }

    @McpTool(name = "clock_status")
    @McpDescription(
        description = """
        |Clocked In: whether the developer's work clock is running, paused (on a break) or stopped
        |(clocked out), since when and by whom, plus time worked today and this week
        |(ISO week, Monday to Sunday).
        """,
    )
    suspend fun clock_status(): ClockStatusDto = statusDto(ClockService.getInstance().status())

    @McpTool(name = "clock_report")
    @McpDescription(
        description = """
        |Clocked In: time worked between two dates, grouped by day or by ISO week, with the split
        |between sessions the developer started and sessions an agent started.
        |Dates are in the developer's local time zone.
        """,
    )
    suspend fun clock_report(
        @McpDescription(description = "First day, inclusive, as YYYY-MM-DD. Defaults to Monday of the current week.")
        from: String? = null,
        @McpDescription(description = "Last day, inclusive, as YYYY-MM-DD. Defaults to today.")
        to: String? = null,
        @McpDescription(description = "`day` or `week`. Defaults to `day`.")
        groupBy: String = "day",
    ): ClockReportResponse {
        val service = ClockService.getInstance()
        val zone = service.zone
        val now = System.currentTimeMillis()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val fromDate = from?.let { Formats.parseDate(it) ?: throw McpExpectedError("Invalid 'from' date: $it. Use YYYY-MM-DD.") }
            ?: Reports.weekStart(today)
        val toDate = to?.let { Formats.parseDate(it) ?: throw McpExpectedError("Invalid 'to' date: $it. Use YYYY-MM-DD.") }
            ?: today
        if (toDate.isBefore(fromDate)) throw McpExpectedError("'to' must not be before 'from'.")
        if (fromDate.plusYears(2).isBefore(toDate)) throw McpExpectedError("The range can be at most two years.")
        val grouping = when (groupBy.trim().lowercase()) {
            "day" -> GroupBy.DAY
            "week" -> GroupBy.WEEK
            else -> throw McpExpectedError("Invalid groupBy: $groupBy. Use `day` or `week`.")
        }
        val periods = Reports.report(service.sessions(), fromDate, toDate, grouping, zone, now)
        val total = periods.fold(Totals()) { acc, p -> acc + p.totals }
        return ClockReportResponse(
            from = fromDate.toString(),
            to = toDate.toString(),
            groupBy = grouping.name.lowercase(),
            periods = periods.map { PeriodDto(it.start.toString(), it.endInclusive.toString(), totalsDto(it.totals)) },
            total = totalsDto(total),
        )
    }

    private suspend fun callerName(): String? = currentCoroutineContext().mcpCallInfoOrNull?.clientInfo?.name

    private fun statusDto(status: ClockStatus): ClockStatusDto {
        val session = status.running
        val pause = status.pause
        return ClockStatusDto(
            state = status.state.name.lowercase(),
            running = status.state == com.sterbenden.clockedin.core.ClockState.RUNNING,
            runningSince = session?.let { Formats.isoInstant(it.start) },
            startedBy = session?.startedBy?.name?.lowercase(),
            startedByAgent = session?.startedByAgent,
            elapsedMinutes = session?.let { Formats.minutes(it.durationMs(status.now)) },
            pausedSince = pause?.let { Formats.isoInstant(it.start) },
            pausedBy = pause?.by?.name?.lowercase(),
            today = totalsDto(status.today),
            thisWeek = totalsDto(status.week),
        )
    }

    private fun totalsDto(t: Totals): TotalsDto {
        // Round the total and the agent part; "me" is the rest, so the parts always add up.
        val total = Formats.minutes(t.totalMs)
        val agent = Formats.minutes(t.agentStartedMs)
        return TotalsDto(total, Formats.duration(t.totalMs), total - agent, agent)
    }
}
