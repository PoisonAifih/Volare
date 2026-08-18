package dev.aifih.volare.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.aifih.volare.R
import dev.aifih.volare.ServiceLocator
import dev.aifih.volare.VolareApplication
import dev.aifih.volare.data.RunEvent
import dev.aifih.volare.data.RunStatus
import dev.aifih.volare.ui.MainActivity
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch


class RunWatchService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val agentId = intent?.getStringExtra(EXTRA_AGENT_ID)
        val runId = intent?.getStringExtra(EXTRA_RUN_ID)
        val agentName = intent?.getStringExtra(EXTRA_AGENT_NAME) ?: "Agent"

        if (agentId.isNullOrBlank() || runId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID_ONGOING, ongoingNotification(agentName))
        watch(agentId, runId, agentName)

        return START_NOT_STICKY
    }

    private fun watch(agentId: String, runId: String, agentName: String) {
        lifecycleScope.launch {
            var completed = false

            ServiceLocator.runStream.stream(agentId, runId)
                .catch { }
                .collect { event ->
                    when (event) {
                        is RunEvent.Completed -> {
                            completed = true
                            notifyTerminal(
                                agentName = agentName,
                                status = event.status,
                                text = event.text,
                                prUrl = event.git?.branches?.firstNotNullOfOrNull { it.prUrl },
                            )
                        }

                        is RunEvent.Failed -> if (event.expired) {
                            val run = runCatching {
                                ServiceLocator.repository.getRun(agentId, runId)
                            }.getOrNull()

                            if (run != null && RunStatus.isTerminal(run.status)) {
                                completed = true
                                notifyTerminal(
                                    agentName = agentName,
                                    status = run.status,
                                    text = run.result,
                                    prUrl = run.git?.branches?.firstNotNullOfOrNull { it.prUrl },
                                )
                            }
                        }

                        else -> Unit
                    }
                }

            if (!completed) {
                val run = runCatching { ServiceLocator.repository.getRun(agentId, runId) }.getOrNull()
                if (run != null && RunStatus.isTerminal(run.status)) {
                    notifyTerminal(
                        agentName = agentName,
                        status = run.status,
                        text = run.result,
                        prUrl = run.git?.branches?.firstNotNullOfOrNull { it.prUrl },
                    )
                }
            }

            stopSelf()
        }
    }

    private fun ongoingNotification(agentName: String): Notification =
        NotificationCompat.Builder(this, VolareApplication.CHANNEL_RUN_WATCH)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle("Watching agent")
            .setContentText(agentName)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun notifyTerminal(
        agentName: String,
        status: String,
        text: String?,
        prUrl: String?,
    ) {
        val manager = getSystemService<NotificationManager>() ?: return

        val title = when (status.uppercase()) {
            RunStatus.FINISHED -> "Agent finished"
            RunStatus.CANCELLED -> "Agent cancelled"
            RunStatus.EXPIRED -> "Agent expired"
            else -> "Agent failed"
        }

        val body = text?.take(400)?.ifBlank { null } ?: agentName

        val notification = NotificationCompat.Builder(this, VolareApplication.CHANNEL_RUN_FINISHED)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle("$title · $agentName")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(prUrl?.let(::openUrlIntent) ?: openAppIntent())
            .build()

        manager.notify(NOTIFICATION_ID_RESULT, notification)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openUrlIntent(url: String): PendingIntent = PendingIntent.getActivity(
        this,
        1,
        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val EXTRA_AGENT_ID = "agentId"
        private const val EXTRA_RUN_ID = "runId"
        private const val EXTRA_AGENT_NAME = "agentName"
        private const val NOTIFICATION_ID_ONGOING = 1001
        private const val NOTIFICATION_ID_RESULT = 1002

        fun start(context: Context, agentId: String, runId: String, agentName: String) {
            val intent = Intent(context, RunWatchService::class.java)
                .putExtra(EXTRA_AGENT_ID, agentId)
                .putExtra(EXTRA_RUN_ID, runId)
                .putExtra(EXTRA_AGENT_NAME, agentName)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RunWatchService::class.java))
        }
    }
}
