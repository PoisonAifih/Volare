package dev.aifih.volare.update

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
                "The APK signature differs from the installed version. Uninstall the app, " +
                    "then install the new APK manually once."

            PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation cancelled"

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "The APK is not compatible with this device"

            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage space"

            PackageInstaller.STATUS_FAILURE_INVALID -> "The APK is corrupt or invalid"

            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                "Installation blocked by the system or device policy"

            else -> detail ?: "Installation failed (code $status)"
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
