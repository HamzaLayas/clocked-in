# Clocked In

Track your working hours in JetBrains IDEs with one clock that you and your AI agents
share. Start, pause and stop it yourself, or let an agent such as Claude Code do it
while it works on your requests.

## Features

- **Toolbar clock:** sits in the top toolbar next to the run controls and shows the
  time worked in the current session, for example `01:12:45`. It stays still during a
  break and goes back to `00:00:00` when you stop. Its icon matches the buttons: ▶
  (green) while you're working, ⏸ (orange) on a break, and ■ when you're clocked out.
- **Start, Pause and Stop buttons** right next to the clock. Pause starts a break inside
  the current session, and Resume carries on with the same session. Stop ends the
  session; the next Start begins a new one.
- **⋮ menu:** This Week (a summary of today and this week), Print Timesheet, Export to CSV, the
  Clocked In tool window, Add Session, and Install Agent Skill.
- **Clocked In tool window:** your current state, today's and this week's totals (split
  into time you started and time agents started), and a list of sessions for any date
  range that you can add to, edit and delete.
- **Paid tracking:** tick sessions as paid, filter to paid or unpaid sessions, and see
  how much of a range is still unpaid.
- **Agent tools:** `clock_in`, `clock_pause`, `clock_out`, `clock_status` and
  `clock_report` on the IDE's built-in MCP server.
- **You stay in control:** every time an agent starts, pauses or stops the clock, you
  get a notification with **Undo**. Agents can't pause or stop a session you started,
  and they can't end a break you took.
- **Agent skill:** run `/clocked-in` in an agent session. The agent then starts or
  resumes the clock when it begins each request and pauses it when it's done.

## Requirements

An IntelliJ-based IDE, version 2026.3 or newer.

## Installation

Clocked In is installed from its GitHub releases. Add them to your IDE once, and the IDE
installs the plugin and offers updates the same way it does for Marketplace plugins.

1. Go to *Settings | Plugins*, click ⚙, and choose *Manage Plugin Repositories…*.
2. Click **+** and add:

   ```
   https://github.com/HamzaLayas/clocked-in/releases/latest/download/updatePlugins.xml
   ```

3. Click **OK**, open the **Marketplace** tab, search for **Clocked In** and click
   **Install**.

New versions then appear under *Settings | Plugins | Installed* and in the IDE's update
notifications.

To install a single version by hand instead, download `clocked-in-<version>.zip` from the
[releases page](https://github.com/HamzaLayas/clocked-in/releases), then go to *Settings | Plugins*,
click ⚙, choose *Install Plugin from Disk…*, and select the ZIP file. Versions installed
this way don't update automatically.

## Tracking your own time

Use the buttons next to the clock in the top toolbar:

- **▶ Start:** starts a new session, or resumes the current one after a break.
- **⏸ Pause:** takes a break. Break time isn't counted as work.
- **■ Stop:** ends the session and resets the clock to `00:00:00`.

Today's and this week's totals are in the clock's tooltip, in **This Week**, and in the
tool window.

The same commands are in *Tools | Clocked In*. <kbd>Ctrl+Alt+Shift+K</kbd> pauses a
running clock and otherwise starts or resumes it. You can change the shortcut, or add
shortcuts for Start, Pause and Stop, in *Settings | Keymap*.

Click the time itself to open **This Week**: today's and this week's totals and your recent
sessions. Close it with ✕, Esc, or by clicking elsewhere.

The **⋮** menu has everything else:

- **This Week:** the same summary.
- **Print Timesheet…:** choose a date range, or **All dates**. The timesheet, with every session, daily
  totals and a grand total, opens in your web browser with its print dialog. Print it,
  or choose **Save as PDF**. This works even if no printer is set up.
- **Export Sessions to CSV…:** save sessions for a date range, or **All dates**, as a
  spreadsheet file. Choose whether to include all, only unpaid, or only paid sessions.
  Start and end times, `break` and `worked` are in hh:mm:ss. `worked` doesn't include
  breaks, and `hours` repeats it as a decimal for adding up. There's also a `paid`
  column.
- **Open Clocked In Tool Window:** see all sessions and totals.
- **Add Session…:** add time you forgot to track.
- **Install Agent Skill…:** see the next section.

In the **Clocked In** tool window each session shows its start, end, break time and
worked time. You can add a session or fix a start or end time.

- **Choose the days:** type a range into the **Sessions from** and **to** fields as
  `yyyy-MM-dd`. By default the list shows this week. Tick **All dates** to show every
  session instead.
- **Totals:** below the fields you'll see the total for the range, how much of it is
  unpaid, and how much is paid.

### Working with several sessions

Tick the checkbox at the start of each row you want, or **Select all**, then use:

- **Mark Paid** or **Mark Unpaid**
- **Delete**

The buttons show how many sessions are ticked, and ask before changing anything.

### Tracking what's been paid

- **Mark one session:** tick its **Paid** checkbox.
- **Mark several at once:** set the range (for example a single day or a whole month),
  choose **Show: Unpaid**, tick **Select all**, then click **Mark Paid**.
- **Find unpaid or paid time:** use **Show** to list only **Unpaid** or **Paid**
  sessions.

To also show the clock in the status bar, right-click the status bar and turn on
**Clocked In**.

## Letting an agent track time

1. **Turn on the IDE's MCP server.** Go to *Settings | Tools | MCP Server*, enable it,
   and connect your agent there. For Claude Code you can also run:

   ```bash
   claude mcp add --transport http idea http://127.0.0.1:64342/stream
   ```

   Use the port shown in the MCP Server settings if it isn't 64342.
2. **Install the skill.** Choose **⋮** → *Install Agent Skill…*:
   - **All Projects:** installs it for Claude Code in every project.
   - **This Project:** installs it in this project's `.claude/skills` folder. Commit it
     to share it with your team.
   - **Copy to Clipboard:** for other agents.
3. **Start tracking.** In an agent session, run `/clocked-in`. You can add a task to the
   same message, for example `/clocked-in fix the login bug`.
4. **Stop tracking.** Run `/clocked-in off` to have the agent pause the clock and stop
   tracking, or `/clocked-in stop` to have it clock you out.

While tracking is on, the agent starts or resumes the clock before it begins each
request and pauses it when it's done, or when it stops to ask you something, so one
session covers its whole run of requests. It only stops the session when you tell it
to. You'll see a notification each time, and you can
undo it.

If you started the clock yourself, it keeps running when the agent finishes. If you
paused it yourself, the agent leaves your break alone.

## Good to know

- **No idle detection.** The clock runs until you or an agent pauses or stops it.
- **Closing the IDE clocks you out.** After a crash, the session ends at the last time
  the IDE was running.
- **Sessions that cross midnight are counted on both days** in totals, the timesheet
  and the CSV export.
- **Weeks run Monday to Sunday.**
- **Each IDE keeps its own clock.** For example, IntelliJ IDEA and Rider each track
  separately.

## Troubleshooting

**The agent says the Clocked In tools aren't available.**
Check that the plugin is enabled, the MCP server is on in *Settings | Tools | MCP
Server*, and your agent is connected to it. Restart the agent session after
connecting.

**The agent gets "Unable to determine the target project".**
The IDE needs to know which project a tool call is for. The skill handles this by
passing any open project, because the clock is the same for all of them. If no
project is open in the IDE, open one.

**The skill doesn't show up as `/clocked-in`.**
Install it again with **⋮** → *Install Agent Skill…*, then start a new agent session.

**The IDE says the installed skill is outdated.**
Click **Update** in the notification. If you've edited the skill yourself, the IDE
leaves it alone.

## Privacy

Your sessions are stored only on your computer, in the IDE's configuration folder
(`clocked-in/sessions.json`). The plugin doesn't send anything over the network.

## License

[MIT](LICENSE)
