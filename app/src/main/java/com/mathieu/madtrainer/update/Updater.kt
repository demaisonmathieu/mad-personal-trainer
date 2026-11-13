package com.mathieu.madtrainer.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mathieu.madtrainer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String, val notes: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * Mise à jour depuis les Releases GitHub (dépôt public).
 * Les tags sont "v1.0.<n>" où n = versionCode (numéro de build GitHub Actions).
 */
object Updater {
    private const val REPO = "demaisonmathieu/mad-personal-trainer"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val APK_NAME = "MADPersonalTrainer.apk"

    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    private val json = Json { ignoreUnknownKeys = true }

    /** Renvoie la dernière release si elle est plus récente que l'app installée, sinon null. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MADPersonalTrainer/$currentVersionName")
        }
        try {
            if (conn.responseCode != 200) error("GitHub a répondu ${conn.responseCode}")
            val root = json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: error("Release sans tag")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: error("Tag inattendu : $tag")
            val apk = root["assets"]?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == APK_NAME }
                ?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
                ?: error("APK absent de la release $tag")
            val notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (code > currentVersionCode) UpdateInfo(code, tag.removePrefix("v"), apk, notes) else null
        } finally {
            conn.disconnect()
        }
    }

    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "MADPersonalTrainer-${info.versionName}.apk")
            val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MADPersonalTrainer/$currentVersionName")
            }
            try {
                if (conn.responseCode != 200) error("Téléchargement refusé (${conn.responseCode})")
                val total = conn.contentLengthLong.takeIf { it > 0 }
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total != null) onProgress(done.toFloat() / total)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            out
        }

    /** Android doit autoriser l'app à installer des APK (une seule fois). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Ouvre l'installeur Android : l'APK s'installe par-dessus (même clé), données conservées. */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
