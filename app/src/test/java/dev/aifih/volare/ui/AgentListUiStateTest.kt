package dev.aifih.volare.ui

import dev.aifih.volare.data.Agent
import dev.aifih.volare.data.AgentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentListUiStateTest {

    @Test
    fun `visible agents hide deactivated unless toggled on`() {
        val agents = listOf(
            Agent(id = "active", status = AgentStatus.ACTIVE),
            Agent(id = "gone", status = AgentStatus.ARCHIVED),
        )

        val hidden = AgentListUiState(agents = agents, showDeactivated = false)
        assertEquals(listOf("active"), hidden.visibleAgents.map { it.id })
        assertTrue(hidden.hasHiddenDeactivated)

        val shown = hidden.copy(showDeactivated = true)
        assertEquals(listOf("active", "gone"), shown.visibleAgents.map { it.id })
        assertFalse(shown.hasHiddenDeactivated)
    }
}
