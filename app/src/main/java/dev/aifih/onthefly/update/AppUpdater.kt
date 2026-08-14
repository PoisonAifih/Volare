package dev.aifih.onthefly.update

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
 * On Android 12 and newer this replaces the app without any confirmation dialog: the
 * documented exemption for an installer "updating itself" applies, provided the app holds
 * `UPDATE_PACKAGES_WITHOUT_USER_ACTION` and the session sets `USER_ACTION_NOT_REQUIRED`.
 * The system can still demand confirmation, so callers must handle
 * [InstallEvent.PendingUserAction].
 *
 * The update must be signed with the same key as the installed build, which is why CI signs
 * releases with a fixed keystore rather than the per-machine debug key. That signature check
 * is what protects this channel: an APK from anywhere else simply fails to install.
 *
 * The releases repository is private, so every request carries a read-only GitHub token and
 * goes through the REST API. `raw.githubusercontent.com` is not usable here because it does
 * not accept token authentication.
 */
class AppUpdater(
    private val context: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val tokenProvider: () -> String?,
) {

    /**
     * Asset downloads answer with a 302 to pre-signed storage. The redirect must be followed
     * by hand, because forwarding the GitHub token to that host makes it reject the request
     * with "only one auth mechanism allowed".
     */
    private val noRedirectClient: OkHttpClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

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
        val request = authorized("$API_BASE/repos/$REPO/contents/$MANIFEST_FILE?ref=main")
            .header("Accept", "application/vnd.github.raw")
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

    /**
     * Downloads the APK, reporting progress as a 0..1 fraction. Progress stays at 0 while the
     * server has not told us the total size.
     */
    suspend fun download(
        manifest: UpdateManifest,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val assetId = manifest.assetId
            ?: throw IOException("latest.json tidak menyertakan assetId, jadi APK-nya tidak bisa diunduh")

        val target = File(context.cacheDir, "update-${manifest.versionCode}.apk")
        if (target.exists()) target.delete()

        openAsset(assetId).use { response ->
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

    private fun openAsset(assetId: Long): Response {
        val request = authorized("$API_BASE/repos/$REPO/releases/assets/$assetId")
            .header("Accept", "application/octet-stream")
            .build()

        val first = noRedirectClient.newCall(request).execute()
        if (!first.isRedirect) return first

        val location = first.header("Location")
        first.close()

        if (location.isNullOrBlank()) {
            throw IOException("GitHub mengarahkan unduhan tanpa alamat tujuan")
        }

        // Deliberately unauthenticated: the redirect target carries its own signature.
        return client.newCall(Request.Builder().url(location).build()).execute()
    }

    private fun authorized(url: String): Request.Builder {
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
            ?: throw MissingUpdateTokenException()

        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", API_VERSION)
    }

    private fun describe(response: Response): IOException = IOException(
        when (response.code) {
            401 -> "Token GitHub tidak valid atau sudah kedaluwarsa."

            403 -> "Token GitHub ditolak. Pastikan izinnya Contents: Read untuk repo $REPO."

            // On a private repo GitHub returns 404 both for a missing file and for a token
            // without access, so the message has to cover both.
            404 -> "Belum ada release, atau token tidak punya akses ke repo $REPO."

            else -> "Gagal menghubungi GitHub (HTTP ${response.code})"
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

    /**
     * FLAG_MUTABLE is required so the system can attach the status extras, and on
     * Android 12+ the confirmation intent, to this PendingIntent.
     */
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
        /** Change this if the releases repository is renamed. */
        const val REPO = "PoisonAifih/OnTheFly-Release"

        const val API_BASE = "https://api.github.com"
        const val API_VERSION = "2022-11-28"
        const val MANIFEST_FILE = "latest.json"
        const val APK_NAME = "onthefly.apk"
    }
}

class MissingUpdateTokenException :
    IOException("Token GitHub belum diisi, jadi update tidak bisa diperiksa.")
