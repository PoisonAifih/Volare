package dev.aifih.onthefly.update

import kotlinx.serialization.Serializable

@Serializable
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,

    val assetId: Long? = null,
    val apkUrl: String? = null,
    val notes: String? = null,
    val publishedAt: String? = null,
)

sealed interface UpdateCheck {
    data class UpToDate(val currentVersionName: String) : UpdateCheck

    data class Available(val manifest: UpdateManifest) : UpdateCheck
}

sealed interface InstallEvent {
    data object Success : InstallEvent

    /** The system decided a confirmation dialog is needed after all. */
    data object PendingUserAction : InstallEvent

    data class Failed(val message: String) : InstallEvent
}
