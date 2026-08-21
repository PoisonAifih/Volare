package dev.aifih.volare.data

import java.io.File

/**
 * Persists chat transcripts for active agents under the account cache directory.
 * Cleared with [clear] on sign-out, and per-agent on deactivate.
 */
class AgentTranscriptStore(private val dir: File) {

    fun load(agentId: String): String? {
        val file = fileFor(agentId)
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun save(agentId: String, text: String) {
        if (text.isEmpty()) {
            delete(agentId)
            return
        }
        dir.mkdirs()
        runCatching { fileFor(agentId).writeText(text) }
    }

    fun delete(agentId: String) {
        fileFor(agentId).delete()
    }

    fun clear() {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(agentId: String): File {
        val safe = agentId.replace(UNSAFE, "_")
        return File(dir, "transcript_$safe.txt")
    }

    private companion object {
        val UNSAFE = Regex("[^A-Za-z0-9._-]")
    }
}
