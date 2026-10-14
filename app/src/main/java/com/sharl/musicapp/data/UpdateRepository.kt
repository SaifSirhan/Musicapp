package com.sharl.musicapp.data

import android.content.Context
import android.util.Log
import com.sharl.musicapp.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
    val sha256: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data object UpToDate : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class Downloaded(val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

class UpdateRepository(private val context: Context) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun reset() {
        _state.value = UpdateState.Idle
    }

    suspend fun check() = withContext(Dispatchers.IO) {
        val url = manifestUrl()
        if (url.isBlank()) {
            _state.value = UpdateState.Failed("OTA not configured")
            return@withContext
        }
        _state.value = UpdateState.Checking
        try {
            val json = get(url)
            val obj = JSONObject(json)
            val info = UpdateInfo(
                versionCode = obj.optInt("versionCode", 0),
                versionName = obj.optString("versionName", ""),
                apkUrl = obj.optString("apkUrl", ""),
                notes = obj.optString("notes", ""),
                sha256 = obj.optString("sha256", ""),
            )
            _state.value = if (info.versionCode > BuildConfig.VERSION_CODE && info.apkUrl.isNotBlank()) {
                UpdateState.Available(info)
            } else {
                UpdateState.UpToDate
            }
        } catch (e: Exception) {
            Log.w(TAG, "check failed", e)
            _state.value = UpdateState.Failed(e.message ?: "Update check failed")
        }
    }

    suspend fun download(info: UpdateInfo) = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "update.apk")
        try {
            val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var written = 0L
                    var lastEmit = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (total > 0 && now - lastEmit > 200) {
                            lastEmit = now
                            _state.value = UpdateState.Downloading(((written * 100) / total).toInt())
                        }
                    }
                }
            }
            conn.disconnect()

            if (info.sha256.isNotBlank() && !info.sha256.equals(sha256(target), ignoreCase = true)) {
                target.delete()
                _state.value = UpdateState.Failed("Downloaded file is corrupt (checksum mismatch).")
                return@withContext
            }
            _state.value = UpdateState.Downloaded(target)
        } catch (e: Exception) {
            Log.w(TAG, "download failed", e)
            target.delete()
            _state.value = UpdateState.Failed(e.message ?: "Download failed")
        }
    }

    private fun manifestUrl(): String = BuildConfig.OTA_MANIFEST_URL.trim()

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) md.update(buf, 0, read)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "UpdateRepository"
    }
}
