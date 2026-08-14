package dev.aifih.onthefly

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.content.getSystemService

class OnTheFlyApplication : Application() {

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
                "Agent selesai",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Memberi tahu saat run agent berakhir"
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RUN_WATCH,
                "Memantau agent",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Notifikasi permanen selama app menjaga koneksi ke run yang berjalan"
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_RUN_FINISHED = "run_finished"
        const val CHANNEL_RUN_WATCH = "run_watch"
    }
}
