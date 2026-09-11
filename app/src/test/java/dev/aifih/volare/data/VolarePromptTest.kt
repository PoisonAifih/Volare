package dev.aifih.volare.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolarePromptTest {

    @Test
    fun withCommitHint_appendsTagAndNoCursorCoauthorInstruction() {
        val result = VolarePrompt.withCommitHint("Fix the login bug")
        assertTrue(result.startsWith("Fix the login bug"))
        assertTrue(result.contains(VolarePrompt.COMMIT_TAG))
        assertTrue(result.contains("append ${VolarePrompt.COMMIT_TAG}"))
        assertTrue(result.contains(VolarePrompt.NO_CURSOR_COAUTHOR_MARKER))
        assertTrue(result.contains("Made-with: Cursor"))
        assertTrue(result.contains("strip those lines before push"))
    }

    @Test
    fun withCommitHint_doesNotDuplicateWhenTagAndMarkerAlreadyPresent() {
        val prompt = "Ship it\n\n${VolarePrompt.COMMIT_TAG}\n${VolarePrompt.NO_CURSOR_COAUTHOR_MARKER}"
        assertEquals(prompt, VolarePrompt.withCommitHint(prompt))
    }

    @Test
    fun withCommitHint_appendsWhenOnlyTagPresent() {
        val prompt = "Ship it\n\n${VolarePrompt.COMMIT_TAG}"
        val result = VolarePrompt.withCommitHint(prompt)
        assertTrue(result.startsWith(prompt))
        assertTrue(result.contains(VolarePrompt.NO_CURSOR_COAUTHOR_MARKER))
        assertTrue(result.contains("---\nVolare:"))
    }

    @Test
    fun withCommitHint_trimsTrailingWhitespaceBeforeAppend() {
        val result = VolarePrompt.withCommitHint("Hello\n\n")
        assertFalse(result.startsWith("Hello\n\n\n"))
        assertTrue(result.startsWith("Hello\n\n---\n"))
    }
}
