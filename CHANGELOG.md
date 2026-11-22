# Changelog

## 0.1.2

- **Export Unpaid Hours** replaces the CSV export. It saves every session that isn't
  marked paid as an Excel spreadsheet to hand in: a summary sheet with each week's total
  and a grand total, then one sheet per week with the hours for each day.
- The This Week window has a working close button and uses the IDE's theme colors.
- Updating the plugin no longer stops a running clock or ends a break. The session
  carries on as if nothing happened.
- After a crash, the session ends at most about 10 seconds before the crash, instead of
  up to a minute.

## 0.1.1

- All durations are shown as hh:mm:ss: worked and break time in the session list, the
  totals, the This Week window, notifications and the printed timesheet.
- Start and end times include seconds.
- CSV export has `break` and `worked` columns in hh:mm:ss, and start and end times with
  seconds. The `hours` column stays for adding up in a spreadsheet.
- When editing a session, seconds are optional.

## 0.1.0

First release.

- One work clock that you and your AI agents share.
- Toolbar clock next to the run controls, showing the time worked in the current
  session, with a play, pause or stop icon showing whether you're working, on a break
  or clocked out. It can also be shown in the status bar.
- Start, Pause and Stop buttons next to the clock. Pause is a break inside the session
  and Resume carries on with it. Stop ends the session and resets the clock.
- Break time is shown for every session and isn't counted as work.
- ⋮ menu with This Week (today's and this week's totals), Print Timesheet, Export Sessions to CSV,
  the Clocked In tool window, Add Session, and Install Agent Skill.
- *Tools | Clocked In* menu with the same commands, and <kbd>Ctrl+Alt+Shift+K</kbd> to
  pause or to start and resume.
- Clocked In tool window with today and this-week totals, split into time you started
  and time agents started. Pick any date range for the session list, or all dates, and
  see its total. You can add, edit and delete sessions there.
- Paid tracking: a Paid checkbox on every session, a filter for paid or unpaid sessions,
  and the unpaid total for the range.
- Tick rows (or Select all) to mark them paid or unpaid, or delete them.
- Printable timesheet for any date range, with every session, daily totals and a grand
  total. It opens in your web browser, where you can print it or save it as a PDF.
- CSV export for any date range or all dates, with `break`, `worked` (hh:mm:ss), `hours` and `paid` columns and a choice of all,
  unpaid or paid sessions. Sessions that cross midnight are split by day.
- MCP tools for agents on the IDE's built-in MCP server: `clock_in`, `clock_pause`,
  `clock_out`, `clock_status` and `clock_report`.
- A notification with Undo whenever an agent starts, pauses or stops the clock. Agents
  can't pause or stop a session you started, or end a break you took.
- `clocked-in` agent skill: run `/clocked-in` in an agent session to have the agent
  track its work. It pauses at the end of each request and only clocks you out when you
  ask. The IDE offers to update installed copies when a newer version ships.
- Closing the IDE clocks you out. After a crash, the session ends at the IDE's last sign
  of life.
