package com.sterbenden.clockedin.skill

import java.nio.file.Files
import java.nio.file.Path

/** The bundled `clocked-in` agent skill and where it gets installed. */
object SkillFiles {
    const val SKILL_NAME = "clocked-in"
    private const val RESOURCE = "/skill/clocked-in/SKILL.md"
    private val VERSION_MARKER = Regex("""<!--\s*clocked-in-skill-version:\s*(\d+)\s*-->""")

    val bundled: String by lazy {
        val stream = SkillFiles::class.java.getResourceAsStream(RESOURCE) ?: error("Missing resource $RESOURCE")
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    val bundledVersion: Int by lazy { versionOf(bundled) ?: error("Bundled skill has no version marker") }

    /** Claude Code skill directory for all projects: `~/.claude/skills/clocked-in/SKILL.md`. */
    fun globalTarget(home: Path = Path.of(System.getProperty("user.home"))): Path =
        home.resolve(".claude").resolve("skills").resolve(SKILL_NAME).resolve("SKILL.md")

    /** Claude Code skill directory for one project: `<project>/.claude/skills/clocked-in/SKILL.md`. */
    fun projectTarget(projectDir: Path): Path =
        projectDir.resolve(".claude").resolve("skills").resolve(SKILL_NAME).resolve("SKILL.md")

    fun versionOf(text: String): Int? = VERSION_MARKER.find(text)?.groupValues?.get(1)?.toIntOrNull()

    enum class State { MISSING, UP_TO_DATE, OUTDATED, MODIFIED }

    /**
     * MISSING: no file. UP_TO_DATE: identical to the bundled skill. OUTDATED: an older
     * version marker. MODIFIED: same or newer version but different text, or no marker.
     */
    fun stateOf(target: Path, bundledText: String = bundled): State {
        if (!Files.exists(target)) return State.MISSING
        val installed = runCatching { Files.readString(target) }.getOrElse { return State.MODIFIED }
        if (installed == bundledText) return State.UP_TO_DATE
        val installedVersion = versionOf(installed) ?: return State.MODIFIED
        val bundledVersion = versionOf(bundledText) ?: return State.MODIFIED
        return if (installedVersion < bundledVersion) State.OUTDATED else State.MODIFIED
    }

    fun write(target: Path, text: String = bundled) {
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }
}
