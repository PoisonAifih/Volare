package dev.aifih.volare.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Checks the releases repository for a newer build and installs it.
 *
 * The releases repository is public, so both requests are anonymous. That keeps the app off
 * the GitHub API and its unauthenticated rate limit entirely.
 */
class AppUpdater(
    private val context: Context,
    private val client: OkHttpClient,
    private val json: Json,
) {

    val currentVersionCode: Long
        get() = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrDefault(0L)

    val currentVersionName: String
        get() = runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
                .orEmpty()
        }.getOrDefault("")

    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(MANIFEST_URL)
            .header("Accept", "application/json")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()

        val manifest = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw describe(response)

            json.decodeFromString(UpdateManifest.serializer(), response.body.string())
        }

        if (manifest.versionCode > currentVersionCode) {
            UpdateCheck.Available(manifest)
        } else {
            UpdateCheck.UpToDate(currentVersionName)
        }
    }

    suspend fun download(
        manifest: UpdateManifest,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val apkUrl = manifest.apkUrl?.takeIf { it.isNotBlank() }
            ?: throw IOException("latest.json has no apkUrl, so the APK cannot be downloaded")

        val target = File(context.cacheDir, "update-${manifest.versionCode}.apk")
        if (target.exists()) target.delete()

        // Redirects to signed storage are followed by OkHttp.
        client.newCall(Request.Builder().url(apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw describe(response)

            val total = response.body.contentLength()
            var downloaded = 0L

            response.body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break

                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(if (total > 0) downloaded.toFloat() / total else 0f)
                    }
                }
            }
        }

        target
    }

    private fun describe(response: Response): IOException = IOException(
        when (response.code) {
            404 -> "No release published in $REPO yet."

            else -> "Could not reach GitHub (HTTP ${response.code})"
        },
    )

    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller

        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(context.packageName)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        val sessionId = installer.createSession(params)

        installer.openSession(sessionId).use { session ->
            session.openWrite(APK_NAME, 0, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }

            session.commit(statusIntentSender(sessionId))
        }
    }

    private fun statusIntentSender(sessionId: Int) = PendingIntent.getBroadcast(
        context,
        sessionId,
        Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag(),
    ).intentSender

    private fun mutabilityFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }

    fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData("package:${context.packageName}".toUri())

    private companion object {
        const val REPO = "PoisonAifih/Volare-ApkRelease"

        const val MANIFEST_URL = "https://raw.githubusercontent.com/$REPO/main/latest.json"

        const val APK_NAME = "volare.apk"
    }
}
