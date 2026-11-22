# Rules for agents working on Clocked In

## Public-facing content

Everything a user can read must be written for users only. This applies every time you
create or change any of the following:

- `README.md`
- `CHANGELOG.md`
- `LICENSE`
- The plugin description and the names and texts of actions, tool windows and widgets in
  `src/main/resources/META-INF/plugin.xml`
- The change notes in `build.gradle.kts`
- Any text shown in the IDE: dialogs, notifications, tooltips, labels, buttons,
  status bar text and error messages
- The agent skill in `src/main/resources/skill/clocked-in/SKILL.md`
- MCP tool and parameter descriptions (`@McpDescription`) and the messages tools return
- Marketplace listing text, release notes and screenshots captions

### Only include what helps a user

Allowed:

- What the plugin does and why it's useful.
- Requirements, compatibility and installation.
- How to use it: menus, shortcuts, buttons, settings and agent setup.
- How it behaves, including limits and edge cases a user can run into.
- Troubleshooting steps a user can take.
- Privacy: what is stored and where, and what is sent over the network.
- Changes a user can see, in the changelog and change notes.

Never include:

- Implementation details such as class, function or package names, data structures or
  code paths. The exception is names users type or see, such as `/clocked-in` and the
  MCP tool names.
- Build, test, CI, signing or publishing steps.
- Test results, test counts, verifier output or audits.
- Development notes, TODOs, plans, pending work, design decisions or reasoning.
- Internal file paths in the source tree.
- Notes about how something was developed, tested or reviewed.

Internal information belongs in `docs/DEVELOPMENT.md` or
`docs/IMPLEMENTATION_PLAN.md`, never in public-facing files.

### How to write it

- Address the reader as "you". Use plain words, short sentences and the present tense.
- Explain a technical term the first time you use it, or use a simpler word.
- Name IDE elements exactly as they appear, with menu paths in the form
  *Tools | Clocked In | Toggle Clock*.
- Describe what the user sees and does, not how the code does it.
- Be accurate. Never describe a feature, shortcut or behavior that doesn't exist. If
  you change behavior, update every public-facing place that describes it in the same
  change.
- Don't oversell. No marketing superlatives, and don't promise behavior the plugin
  doesn't guarantee.

### Keep public-facing files consistent

- **When you bump `pluginVersion`:** update `CHANGELOG.md` and the change notes in
  `build.gradle.kts` together. They must describe the same changes.
- **When you change a feature:** check the README, the plugin description and the skill
  for anything that now describes it wrongly.
- **When you change the skill:** raise its `clocked-in-skill-version` number so users
  are offered the update.

### Check before you finish

Before you finish a change, reread every public-facing file you touched and ask: "Would
a user who never sees the source code understand this and find it useful?" Remove
anything that fails that test.
