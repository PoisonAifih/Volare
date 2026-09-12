package dev.aifih.volare.data

object VolarePrompt {
    const val COMMIT_TAG = "[via Volare]"

    const val NO_CURSOR_COAUTHOR_MARKER = "Do not add Co-authored-by: Cursor"

    private const val COMMIT_HINT =
        "Volare: append $COMMIT_TAG to every git commit message body for this run. " +
            "Do not add Co-authored-by: Cursor, Made-with: Cursor, or any Cursor attribution " +
            "trailer to git commits or pull requests for this run. If tooling injects them, " +
            "strip those lines before push (for example with a commit-msg hook). " +
            "Do not author or commit as Cursor Agent <cursoragent@cursor.com>; use the git " +
            "author identity configured in the repository."

    fun withCommitHint(prompt: String): String {
        val trimmed = prompt.trimEnd()
        if (trimmed.contains(COMMIT_TAG) && trimmed.contains(NO_CURSOR_COAUTHOR_MARKER)) {
            return trimmed
        }
        return "$trimmed\n\n---\n$COMMIT_HINT"
    }
}
