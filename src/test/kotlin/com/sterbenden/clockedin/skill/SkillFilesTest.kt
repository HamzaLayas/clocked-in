package com.sterbenden.clockedin.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class SkillFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `bundled skill has frontmatter, version and only user invocation`() {
        val text = SkillFiles.bundled
        assertTrue(text.startsWith("---\nname: clocked-in\n"))
        assertTrue(text.contains("\ndisable-model-invocation: true\n"))
        assertEquals(3, SkillFiles.bundledVersion)
        for (tool in listOf("clock_in", "clock_pause", "clock_out", "clock_status", "clock_report")) {
            assertTrue("mentions $tool", text.contains("`$tool`"))
        }
    }

    @Test
    fun `install targets follow the Claude Code layout`() {
        val home = tmp.root.toPath()
        assertEquals(home.resolve(".claude/skills/clocked-in/SKILL.md"), SkillFiles.globalTarget(home))
        assertEquals(home.resolve("p/.claude/skills/clocked-in/SKILL.md"), SkillFiles.projectTarget(home.resolve("p")))
    }

    @Test
    fun `state detection`() {
        val target = tmp.root.toPath().resolve("SKILL.md")
        val v2 = "a\n<!-- clocked-in-skill-version: 2 -->\nb"
        assertEquals(SkillFiles.State.MISSING, SkillFiles.stateOf(target, v2))
        Files.writeString(target, v2)
        assertEquals(SkillFiles.State.UP_TO_DATE, SkillFiles.stateOf(target, v2))
        Files.writeString(target, "old\n<!-- clocked-in-skill-version: 1 -->")
        assertEquals(SkillFiles.State.OUTDATED, SkillFiles.stateOf(target, v2))
        Files.writeString(target, "edited\n<!-- clocked-in-skill-version: 2 -->")
        assertEquals(SkillFiles.State.MODIFIED, SkillFiles.stateOf(target, v2))
        Files.writeString(target, "no marker")
        assertEquals(SkillFiles.State.MODIFIED, SkillFiles.stateOf(target, v2))
    }
}
