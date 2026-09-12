package dev.aifih.volare.data

object VolarePrompt {
    const val COMMIT_TAG = "[via Volare]"

    const val NO_COAUTHOR_MARKER = "Do not add any Co-authored-by trailer"

    private const val COMMIT_HINT =
        "Volare: append $COMMIT_TAG to every git commit message body for this run. " +
            "$NO_COAUTHOR_MARKER to any git commit or pull request for this run, not even for " +
            "the repository owner, and do not add Made-with: Cursor or any other Cursor " +
            "attribution trailer. If tooling injects them, strip those lines before push " +
            "(for example with a commit-msg hook). " +
            "Do not author or commit as Cursor Agent <cursoragent@cursor.com>; use the git " +
            "author identity configured in the repository."

    fun withCommitHint(prompt: String): String {
        val trimmed = prompt.trimEnd()
        if (trimmed.contains(COMMIT_TAG) && trimmed.contains(NO_COAUTHOR_MARKER)) {
            return trimmed
        }
        return "$trimmed\n\n---\n$COMMIT_HINT"
    }
}
