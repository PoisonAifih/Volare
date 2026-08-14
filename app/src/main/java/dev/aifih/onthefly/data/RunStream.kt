package dev.aifih.onthefly.data

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

sealed interface RunEvent {
    data class Status(val status: String) : RunEvent

    data class Assistant(val text: String) : RunEvent

    data class Thinking(val text: String) : RunEvent

    data class Tool(val callId: String, val name: String, val status: String) : RunEvent

    data class Completed(val status: String, val text: String?, val git: GitInfo?) : RunEvent

    /** [expired] means the retention window elapsed; read terminal state with `GET run` instead. */
    data class Failed(val message: String, val expired: Boolean = false) : RunEvent
}

/**
 * Streams one run as Server-Sent Events.
 *
 * Reconnects transparently using `Last-Event-ID` so a phone switching between Wi-Fi and
 * mobile data does not lose the transcript. A `410` means the stream retention window has
 * passed, which is not retryable — the caller should fall back to reading the run directly.
 */
class RunStream(
    private val api: CursorApi,
    private val client: OkHttpClient,
    private val json: Json,
) {

    fun stream(agentId: String, runId: String): Flow<RunEvent> = channelFlow {
        var lastEventId: String? = null
        var attempt = 0
        val finished = AtomicBoolean(false)

        while (!finished.get() && attempt <= MAX_RETRIES) {
            val outcome = CompletableDeferred<Outcome>()

            val builder = Request.Builder()
                .url(api.streamUrl(agentId, runId))
                .header("Authorization", api.authHeader())
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-store")

            lastEventId?.let { builder.header("Last-Event-ID", it) }

            val listener = object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    attempt = 0
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    if (id != null) lastEventId = id

                    when (type) {
                        "done" -> finished.set(true)
                        else -> parse(type, data)?.let { event ->
                            if (event is RunEvent.Completed) finished.set(true)
                            trySend(event)
                        }
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    outcome.complete(Outcome.Closed)
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?,
                ) {
                    outcome.complete(Outcome.Failure(response?.code, t?.message))
                }
            }

            val source = EventSources.createFactory(client).newEventSource(builder.build(), listener)
            val result = try {
                outcome.await()
            } finally {
                source.cancel()
            }

            if (finished.get()) break

            when (result) {
                is Outcome.Closed -> break

                is Outcome.Failure -> when {
                    result.code == 410 -> {
                        send(
                            RunEvent.Failed(
                                message = "Stream sudah kedaluwarsa, memuat status akhir",
                                expired = true,
                            ),
                        )
                        break
                    }

                    result.code == 401 || result.code == 403 -> {
                        send(RunEvent.Failed("API key ditolak"))
                        break
                    }

                    result.code == 400 && lastEventId != null -> {
                        // invalid_last_event_id: restart the stream from the beginning.
                        lastEventId = null
                        attempt++
                    }

                    attempt >= MAX_RETRIES -> {
                        send(
                            RunEvent.Failed(
                                result.message ?: "Koneksi stream terputus",
                            ),
                        )
                        break
                    }

                    else -> {
                        attempt++
                        delay(RETRY_DELAY_MS * attempt)
                    }
                }
            }
        }
    }

    private fun parse(type: String?, data: String): RunEvent? = runCatching {
        when (type) {
            "status" -> RunEvent.Status(
                json.decodeFromString(SseStatusPayload.serializer(), data).status,
            )

            "assistant" -> RunEvent.Assistant(
                json.decodeFromString(SseTextPayload.serializer(), data).text,
            )

            "thinking" -> RunEvent.Thinking(
                json.decodeFromString(SseTextPayload.serializer(), data).text,
            )

            "tool_call" -> json.decodeFromString(SseToolCallPayload.serializer(), data).let {
                RunEvent.Tool(it.callId, it.name, it.status)
            }

            "result" -> json.decodeFromString(SseResultPayload.serializer(), data).let {
                RunEvent.Completed(it.status, it.text, it.git)
            }

            "error" -> json.decodeFromString(SseErrorPayload.serializer(), data).let {
                RunEvent.Failed(it.message ?: it.code ?: "Stream error")
            }

            // "heartbeat" keeps the connection alive and "interaction_update" duplicates the
            // simplified events above, so both are ignored.
            else -> null
        }
    }.getOrNull()

    private sealed interface Outcome {
        data object Closed : Outcome

        data class Failure(val code: Int?, val message: String?) : Outcome
    }

    private companion object {
        const val MAX_RETRIES = 4
        const val RETRY_DELAY_MS = 1_500L
    }
}
