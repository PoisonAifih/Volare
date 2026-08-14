package dev.aifih.onthefly.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Receives the outcome of a [PackageInstaller] session. The session runs outside the
 * Activity lifecycle, so results are relayed through a process-wide flow that the UI
 * collects.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> emit(InstallEvent.Success)

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                emit(InstallEvent.PendingUserAction)

                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmation?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(it) }
                }
            }

            else -> emit(InstallEvent.Failed(describe(status, intent)))
        }
    }

    private fun describe(status: Int, intent: Intent): String {
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        return when (status) {
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "Tanda tangan APK berbeda dari versi yang terpasang. Uninstall aplikasi " +
                    "lalu pasang APK baru sekali secara manual."

            PackageInstaller.STATUS_FAILURE_ABORTED -> "Instalasi dibatalkan"

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "APK tidak kompatibel dengan perangkat ini"

            PackageInstaller.STATUS_FAILURE_STORAGE -> "Ruang penyimpanan tidak cukup"

            PackageInstaller.STATUS_FAILURE_INVALID -> "APK rusak atau tidak valid"

            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                "Instalasi diblokir oleh sistem atau kebijakan perangkat"

            else -> detail ?: "Instalasi gagal (kode $status)"
        }
    }

    private fun emit(event: InstallEvent) {
        events.tryEmit(event)
    }

    companion object {
        private val events = MutableSharedFlow<InstallEvent>(
            replay = 0,
            extraBufferCapacity = 8,
        )

        val installEvents: SharedFlow<InstallEvent> = events
    }
}
