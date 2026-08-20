package dev.aifih.volare.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Test
    fun `unknown response fields are ignored`() {
        val payload = """
            {
              "id": "run-1",
              "agentId": "bc-1",
              "status": "FINISHED",
              "someFutureField": { "nested": true }
            }
        """.trimIndent()

        val run = json.decodeFromString(Run.serializer(), payload)

        assertEquals("run-1", run.id)
        assertEquals("FINISHED", run.status)
    }

    @Test
    fun `terminal run exposes branch and pull request`() {
        val payload = """
            {
              "id": "run-1",
              "status": "FINISHED",
              "durationMs": 12357,
              "result": "Added README.md",
              "git": {
                "branches": [
                  {
                    "repoUrl": "github.com/owner/repo",
                    "branch": "cursor/add-readme-a1b2",
                    "prUrl": "https://github.com/owner/repo/pull/123"
                  }
                ]
              }
            }
        """.trimIndent()

        val run = json.decodeFromString(Run.serializer(), payload)

        assertEquals("cursor/add-readme-a1b2", run.git?.branches?.first()?.branch)
        assertEquals("https://github.com/owner/repo/pull/123", run.git?.branches?.first()?.prUrl)
    }

    @Test
    fun `absent nextCursor means no more pages`() {
        val payload = """{ "items": [] }"""

        val response = json.decodeFromString(AgentListResponse.serializer(), payload)

        assertNull(response.nextCursor)
        assertTrue(response.items.isEmpty())
    }

    @Test
    fun `create run request can request pull request creation`() {
        val request = CreateRunRequest(
            prompt = Prompt("Open a pull request for the current branch."),
            autoCreatePR = true,
        )

        val encoded = json.encodeToString(CreateRunRequest.serializer(), request)

        assertTrue(encoded.contains("\"autoCreatePR\":true"))
    }

    @Test
    fun `legacy ask mode maps to agent`() {
        assertEquals(AgentMode.AGENT, AgentMode.fromString("ask"))
    }

    @Test
    fun `only agent and plan modes are selectable`() {
        assertEquals(listOf(AgentMode.AGENT, AgentMode.PLAN), AgentMode.selectableModes)
    }

    @Test
    fun `create request omits null fields so the server applies its defaults`() {
        val request = CreateAgentRequest(
            prompt = Prompt("fix login"),
            repos = listOf(RepoRef(url = "https://github.com/owner/repo", startingRef = "main")),
        )

        val encoded = json.encodeToString(CreateAgentRequest.serializer(), request)

        assertFalse(encoded.contains("model"))
        assertFalse(encoded.contains("mode"))
        assertTrue(encoded.contains("\"startingRef\":\"main\""))
    }

    @Test
    fun `no-repo create request omits repos entirely`() {
        val request = CreateAgentRequest(
            prompt = Prompt("Explain CAP theorem"),
            model = ModelSelection("composer-1"),
        )

        val encoded = json.encodeToString(CreateAgentRequest.serializer(), request)

        assertFalse(encoded.contains("repos"))
        assertFalse(encoded.contains("\"mode\""))
        assertTrue(encoded.contains("\"id\":\"composer-1\""))
    }

    @Test
    fun `model selection encodes parameters`() {
        val selection = ModelSelection(
            id = "claude-4-sonnet-thinking",
            params = listOf(ModelParam("reasoning", "high")),
        )

        val encoded = json.encodeToString(ModelSelection.serializer(), selection)

        assertTrue(encoded.contains("\"id\":\"claude-4-sonnet-thinking\""))
        assertTrue(encoded.contains("\"id\":\"reasoning\""))
        assertTrue(encoded.contains("\"value\":\"high\""))
    }

    @Test
    fun `agent kind prefs round trip`() {
        assertEquals(AgentKind.CODING, AgentKind.fromString(null))
        assertEquals(AgentKind.GENERAL, AgentKind.fromString("general"))
        assertEquals(AgentKind.CODING, AgentKind.fromString("coding"))
        assertEquals(
            listOf(AgentKind.CODING, AgentKind.GENERAL),
            AgentKind.selectableKinds,
        )
    }

    @Test
    fun `git info can expose multiple pull requests`() {
        val payload = """
            {
              "id": "run-1",
              "status": "FINISHED",
              "git": {
                "branches": [
                  {
                    "branch": "cursor/first-aaaa",
                    "prUrl": "https://github.com/owner/repo/pull/1"
                  },
                  {
                    "branch": "cursor/second-bbbb",
                    "prUrl": "https://github.com/owner/repo/pull/2"
                  }
                ]
              }
            }
        """.trimIndent()

        val run = json.decodeFromString(Run.serializer(), payload)
        val urls = run.git?.branches?.mapNotNull { it.prUrl }.orEmpty()

        assertEquals(2, urls.size)
        assertEquals("https://github.com/owner/repo/pull/2", urls.last())
    }

    @Test
    fun `repository url renders as owner slash name`() {
        assertEquals(
            "PoisonAifih/Volare",
            RepositoryItem("https://github.com/PoisonAifih/Volare").shortName,
        )
        assertEquals(
            "PoisonAifih/Volare",
            RepositoryItem("https://github.com/PoisonAifih/Volare.git").shortName,
        )
    }

    @Test
    fun `only documented terminal statuses stop the stream`() {
        assertTrue(RunStatus.isTerminal("FINISHED"))
        assertTrue(RunStatus.isTerminal("ERROR"))
        assertTrue(RunStatus.isTerminal("CANCELLED"))
        assertTrue(RunStatus.isTerminal("EXPIRED"))

        assertFalse(RunStatus.isTerminal("RUNNING"))
        assertFalse(RunStatus.isTerminal("CREATING"))
        assertFalse(RunStatus.isTerminal(null))

        assertFalse(RunStatus.isTerminal("PAUSED"))
    }
}
