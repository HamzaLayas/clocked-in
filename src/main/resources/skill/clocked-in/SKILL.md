---
name: clocked-in
description: Track the user's working time with the Clocked In plugin in their JetBrains IDE for the rest of this session. You start or resume the clock when you begin each request and pause it when you're done. Run with "off" to stop tracking, or "stop" to clock the user out.
disable-model-invocation: true
---

<!-- clocked-in-skill-version: 3 -->

# Clocked In

The user wants their working time tracked while you work in this session. The
Clocked In plugin adds these tools to the JetBrains IDE's MCP server:

- `clock_in`: starts a work session, or resumes the current one after a break.
- `clock_pause`: starts a break. The session stays open and the next `clock_in` resumes
  it.
- `clock_out`: ends the session. The next `clock_in` starts a new one.
- `clock_status`: shows whether the clock is running, paused or stopped, plus today's
  and this week's totals.
- `clock_report`: shows totals per day or week for a date range.

The user and every agent share one clock. The user sees a notification with Undo
for each change you make.

**Pause, don't stop.** At the end of a request, always use `clock_pause`. Only call
`clock_out` when the user explicitly asks you to stop the clock or clock them out.

**projectPath:** the IDE's MCP server needs a project for every tool call. If a call
fails with "Unable to determine the target project", call it again with
`projectPath` set to any project path listed in that error. Keep using that path for
the rest of the session. The clock is the same for every project, so it doesn't
matter which one you pick.

## When the user runs this skill

1. **`off`:** call `clock_pause`, confirm in one line that tracking is off, and stop
   following the rules below for the rest of the session.
2. **`stop`:** call `clock_out`, confirm in one line that the user is clocked out,
   and stop following the rules below for the rest of the session.
3. **Anything else:** call `clock_status`.
   - If the tool isn't available, tell the user to install the Clocked In plugin,
     enable the IDE's MCP server (Settings | Tools | MCP Server) and connect this
     agent to it, then stop.
   - If the error says no project is open in the IDE, ask the user to open any
     project there, then stop.
4. Confirm in one line that tracking is on for this session. Then carry on with any
   task the user gave alongside the skill, following the rules below.

## Rules for the rest of this session

- **Starting a request:** call `clock_in` before your first action on each new user
  request.
  - `already_running`: the clock was already on. That's fine.
  - `on_break_by_me`: the user is on a break they took themselves. Leave it, don't
    retry, and carry on with the request.
- **Finishing a request:** call `clock_pause` once your final answer for the request
  is ready, right before you send it. If the outcome is `left_running_started_by_me`,
  the user clocked in themselves and the clock stays on. Don't retry, and don't
  mention it.
- **Waiting on the user:** if you have to stop and ask the user something before you
  can continue, call `clock_pause` first. Call `clock_in` again when they reply.
- **Clocking out:** call `clock_out` only when the user explicitly tells you to stop
  the clock, clock out or end the day.
- Don't talk about the clock in your replies unless a call fails or the user asks.
- If the user asks how much they've worked, use `clock_status` or `clock_report`.
