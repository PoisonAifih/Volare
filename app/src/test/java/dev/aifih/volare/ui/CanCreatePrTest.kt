package dev.aifih.volare.ui

import dev.aifih.volare.data.Agent
import dev.aifih.volare.data.GitBranch
import dev.aifih.volare.data.GitInfo
import dev.aifih.volare.data.RepoRef
import dev.aifih.volare.data.Run
import dev.aifih.volare.data.RunStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanCreatePrTest {

    @Test
    fun showsWhenBranchHasChangesButNoPr() {
        val state = detailState(
            branches = listOf(GitBranch(branch = "cursor/fix-1")),
            status = RunStatus.FINISHED,
        )

        assertTrue(state.canCreatePr)
        assertFalse(state.hasExistingPr)
    }

    @Test
    fun hidesWhenPrAlreadyExistsAndNoFollowUp() {
        val state = detailState(
            branches = listOf(
                GitBranch(
                    branch = "cursor/fix-1",
                    prUrl = "https://github.com/org/repo/pull/1",
                ),
            ),
            status = RunStatus.FINISHED,
        )

        assertTrue(state.hasExistingPr)
        assertFalse(state.canCreatePr)
    }

    @Test
    fun showsAgainAfterFollowUpWhenPrExists() {
        val state = detailState(
            branches = listOf(
                GitBranch(
                    branch = "cursor/fix-1",
                    prUrl = "https://github.com/org/repo/pull/1",
                ),
            ),
            status = RunStatus.FINISHED,
            hasChangesSincePr = true,
        )

        assertTrue(state.canCreatePr)
    }

    @Test
    fun hidesWhileRunIsActive() {
        val state = detailState(
            branches = listOf(GitBranch(branch = "cursor/fix-1")),
            status = RunStatus.RUNNING,
        )

        assertFalse(state.canCreatePr)
    }

    @Test
    fun hidesForRepoLessAgents() {
        val state = detailState(
            agent = Agent(id = "bc-1", repos = emptyList()),
            branches = listOf(GitBranch(branch = "cursor/fix-1")),
            status = RunStatus.FINISHED,
        )

        assertTrue(state.isRepoLess)
        assertFalse(state.canCreatePr)
    }

    private fun detailState(
        agent: Agent = Agent(
            id = "bc-1",
            repos = listOf(RepoRef(url = "https://github.com/org/repo")),
        ),
        branches: List<GitBranch>,
        status: String,
        hasChangesSincePr: Boolean = false,
    ): AgentDetailUiState = AgentDetailUiState(
        agent = agent,
        run = Run(
            id = "run-1",
            status = status,
            git = GitInfo(branches = branches),
        ),
        status = status,
        hasChangesSincePr = hasChangesSincePr,
    )
}
