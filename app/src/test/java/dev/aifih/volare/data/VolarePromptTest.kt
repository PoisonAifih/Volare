package dev.aifih.volare.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolarePromptTest {

    @Test
    fun withCommitHint_appendsTagInstruction() {
        val result = VolarePrompt.withCommitHint("Fix the login bug")
        assertTrue(result.startsWith("Fix the login bug"))
        assertTrue(result.contains(VolarePrompt.COMMIT_TAG))
        assertTrue(result.contains("append ${VolarePrompt.COMMIT_TAG}"))
    }

    @Test
    fun withCommitHint_doesNotDuplicateWhenTagAlreadyPresent() {
        val prompt = "Ship it\n\n${VolarePrompt.COMMIT_TAG}"
        assertEquals(prompt, VolarePrompt.withCommitHint(prompt))
    }

    @Test
    fun withCommitHint_trimsTrailingWhitespaceBeforeAppend() {
        val result = VolarePrompt.withCommitHint("Hello\n\n")
        assertFalse(result.startsWith("Hello\n\n\n"))
        assertTrue(result.startsWith("Hello\n\n---\n"))
    }
}
