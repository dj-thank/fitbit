package dev.rambo.airposture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Local-first diagnostic recorder.
 *
 * Files live in app-private storage. A shareable ZIP is created only when the
 * user taps the share button. Bluetooth addresses are hashed with a per-session
 * random salt so the exported log is useful for correlation without exposing a
 * stable device identifier.
 */
class DiagnosticsLogger(private val context: Context) {
    private val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
    private var salt = ""
    private var sessionId = ""
    private var logFile: File? = null
    private var writer: BufferedWriter? = null

    @Synchronized
    fun startNewSession(): File {
        writer?.flush()
        writer?.close()
        salt = UUID.randomUUID().toString()
        val stamp = utcFileStamp()
        sessionId = "air-$stamp-${UUID.randomUUID().toString().take(8)}"
        val f = File(dir, "$sessionId.jsonl")
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(f, false), Charsets.UTF_8))
        logFile = f
        event(
            "session_start",
            mapOf(
                "session_id" to sessionId,
                "app_version" to BuildConfig.VERSION_NAME,
                "app_version_code" to BuildConfig.VERSION_CODE,
                "android_sdk" to Build.VERSION.SDK_INT,
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "device" to Build.DEVICE,
            ),
        )
        return f
    }

    @Synchronized
    fun currentSessionId(): String {
        if (writer == null) startNewSession()
        return sessionId
    }

    @Synchronized
    fun isCurrentSession(expectedSessionId: String): Boolean =
        writer != null && sessionId == expectedSessionId

    /** The session check and write share the same lock as startNewSession(). */
    @Synchronized
    fun event(
        type: String,
        fields: Map<String, Any?> = emptyMap(),
        expectedSessionId: String? = null,
    ): Boolean {
        if (expectedSessionId != null && !isCurrentSession(expectedSessionId)) return false
        if (writer == null) startNewSession()
        val obj = JSONObject()
        obj.put("schema", 1)
        obj.put("session_id", sessionId)
        obj.put("utc_ms", System.currentTimeMillis())
        obj.put("elapsed_ns", SystemClock.elapsedRealtimeNanos())
        obj.put("type", type)
        fields.forEach { (k, v) -> obj.put(k, JSONObject.wrap(v)) }
        writer?.apply {
            write(obj.toString())
            newLine()
            flush()
        }
        return true
    }

    @Synchronized
    fun bleAddressToken(address: String): String {
        if (writer == null) startNewSession()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$salt:$address".toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun currentLogFile(): File? = logFile

    /** Create one ZIP that the user can upload directly to ChatGPT. */
    @Synchronized
    fun createShareZip(): File {
        writer?.flush()
        val source = requireNotNull(logFile) { "No diagnostic session" }
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val zip = File(exportDir, "${source.nameWithoutExtension}-share.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("${source.nameWithoutExtension}.jsonl"))
            source.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("README.txt"))
            val readme = """
                Fitbit Air Posture diagnostic export
                Schema: 1
                Session: $sessionId

                Upload this ZIP to ChatGPT for protocol/packet correlation analysis.
                Useful markers: marker_neutral, marker_forward, marker_walk, marker_fitbit_alarm.
                Bluetooth MAC addresses are not exported; device tokens are session-scoped hashes.
                No health-account credentials or Google account tokens are recorded by this app.
            """.trimIndent().toByteArray(Charsets.UTF_8)
            zos.write(readme)
            zos.closeEntry()
        }
        event("export_created", mapOf("filename" to zip.name, "bytes" to zip.length()))
        return zip
    }

    fun shareIntent(zip: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            zip,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Fitbit Air posture BLE diagnostics")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun utcFileStamp(): String {
        val format = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}
