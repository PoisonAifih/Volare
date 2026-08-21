package dev.aifih.volare.data

object VolarePrompt {
    const val COMMIT_TAG = "[via Volare]"

    private const val COMMIT_HINT =
        "Volare: append $COMMIT_TAG to every git commit message body for this run."

    fun withCommitHint(prompt: String): String {
        val trimmed = prompt.trimEnd()
        if (trimmed.contains(COMMIT_TAG)) return trimmed
        return "$trimmed\n\n---\n$COMMIT_HINT"
    }
}
