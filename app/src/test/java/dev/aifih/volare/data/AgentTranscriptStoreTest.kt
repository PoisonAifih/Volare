package dev.aifih.volare.data

import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTranscriptStoreTest {

    @Test
    fun `save load and delete transcript for an agent`() {
        val dir = createTempDirectory("volare-transcripts").toFile()
        val store = AgentTranscriptStore(dir)

        assertNull(store.load("bc-1"))

        store.save("bc-1", "hello chat")
        assertEquals("hello chat", store.load("bc-1"))

        store.delete("bc-1")
        assertNull(store.load("bc-1"))
    }

    @Test
    fun `empty save removes the file`() {
        val dir = createTempDirectory("volare-transcripts").toFile()
        val store = AgentTranscriptStore(dir)

        store.save("bc-1", "kept")
        store.save("bc-1", "")
        assertNull(store.load("bc-1"))
    }

    @Test
    fun `clear removes every transcript`() {
        val dir = createTempDirectory("volare-transcripts").toFile()
        val store = AgentTranscriptStore(dir)

        store.save("bc-1", "one")
        store.save("bc-2", "two")
        store.clear()

        assertNull(store.load("bc-1"))
        assertNull(store.load("bc-2"))
        assertTrue(dir.listFiles().isNullOrEmpty() || dir.listFiles()!!.none { it.isFile })
    }

    @Test
    fun `unsafe agent ids are sanitised in the filename`() {
        val dir = createTempDirectory("volare-transcripts").toFile()
        val store = AgentTranscriptStore(dir)

        store.save("bc/../evil", "safe")
        val files = dir.listFiles()?.map { it.name }.orEmpty()
        assertEquals(listOf("transcript_bc_.._evil.txt"), files)
        assertEquals("safe", store.load("bc/../evil"))
    }
}
