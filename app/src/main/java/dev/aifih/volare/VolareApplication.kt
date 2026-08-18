package dev.aifih.volare

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.content.getSystemService

class VolareApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService<NotificationManager>() ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RUN_FINISHED,
                "Agent finished",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notifies you when an agent run ends"
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RUN_WATCH,
                "Watching agent",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Ongoing notification while the app keeps a connection to a " +
                    "running agent"
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_RUN_FINISHED = "run_finished"
        const val CHANNEL_RUN_WATCH = "run_watch"
    }
}
