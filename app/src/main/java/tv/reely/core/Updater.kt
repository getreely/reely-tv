package tv.reely.core

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * What is published at the update address.
 *
 * A manifest beside the APK is the only way to know a build's version without downloading
 * and unpacking it, so that is looked for first. Where there is none — an address with
 * nothing but an APK on it — the file's own size and date are all there is to go on, and
 * the choice of whether to install it belongs to whoever is reading them.
 */
data class UpdateInfo(
    val url: String,
    val versionCode: Int?,
    val versionName: String?,
    val notes: String?,
    val sizeBytes: Long,
    val published: String?,
) {
    /** True only when a manifest said so. An unknown version is never assumed newer. */
    fun isNewerThan(installed: Int): Boolean = versionCode != null && versionCode > installed

    val describesItself: Boolean get() = versionCode != null
}

object Updater {

    /** The manifest is the APK's own name with a different extension. */
    private fun manifestUrl(apkUrl: String): String = apkUrl.replaceAfterLast('.', "json")

    suspend fun check(apkUrl: String): UpdateInfo = withContext(Dispatchers.IO) {
        val manifest = runCatching { readManifest(apkUrl) }.getOrNull()
        if (manifest != null) return@withContext manifest

        // No manifest: ask the server what it is holding without fetching it.
        val request = Request.Builder().url(apkUrl).head().build()
        Http.client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "The update server answered ${response.code}." }
            UpdateInfo(
                url = apkUrl,
                versionCode = null,
                versionName = null,
                notes = null,
                sizeBytes = response.header("Content-Length")?.toLongOrNull() ?: 0,
                published = response.header("Last-Modified"),
            )
        }
    }

    private fun readManifest(apkUrl: String): UpdateInfo? {
        val request = Request.Builder().url(manifestUrl(apkUrl)).get().build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = JSONObject(response.body?.string().orEmpty())
            return UpdateInfo(
                url = json.optString("url").takeIf(String::isNotBlank) ?: apkUrl,
                versionCode = json.optInt("versionCode").takeIf { it > 0 },
                versionName = json.optString("versionName").takeIf(String::isNotBlank),
                notes = json.optString("notes").takeIf(String::isNotBlank),
                sizeBytes = json.optLong("sizeBytes"),
                published = json.optString("published").takeIf(String::isNotBlank),
            )
        }
    }

    /**
     * Pulls the APK into the cache. It goes to a fixed name, so a half-finished download
     * from a previous attempt is overwritten rather than accumulating.
     */
    suspend fun download(
        context: Context,
        url: String,
        onProgress: (read: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(folder, "reely-update.apk")

        val request = Request.Builder().url(url).get().build()
        Http.client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "The update server answered ${response.code}." }
            val body = response.body ?: error("The update server sent nothing.")
            val total = body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        onProgress(read, total)
                    }
                }
            }
        }
        target
    }

    /**
     * Hands the file to the system installer. Android will not take a file:// path from
     * another app's storage, so it goes through this app's provider, and Fire OS will ask
     * once for permission to install from here before it will go any further.
     */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
