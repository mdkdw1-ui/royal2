package com.example.helper.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val MAX_LOGS = 200
    private val logs = ArrayDeque<String>(MAX_LOGS)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun d(message: String) = add("D", message)
    fun d(message: String, t: Throwable) = add("D", "$message | ${t.message}")

    fun e(message: String) = add("E", message)
    fun e(message: String, t: Throwable) = add("E", "$message | ${t.javaClass.simpleName}: ${t.message}")

    fun w(message: String) = add("W", message)
    fun w(message: String, t: Throwable) = add("W", "$message | ${t.message}")

    private fun add(level: String, message: String) {
        val entry = "[${timeFormat.format(Date())}][$level] $message"
        synchronized(logs) {
            if (logs.size >= MAX_LOGS) logs.removeFirst()
            logs.addLast(entry)
        }
    }

    fun getAll(): List<String> = synchronized(logs) { logs.toList() }

    fun clear() = synchronized(logs) { logs.clear() }

    fun getAsText(): String = getAll().joinToString("\n")

    fun copyToClipboard(context: Context): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("OOXOO Logs", getAsText()))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun saveToDownloads(context: Context): String? {
        return try {
            val filename = "ooxoo_log_${System.currentTimeMillis()}.txt"
            val content = getAsText()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    "Download/$filename"
                } else {
                    fallbackSave(context, filename, content)
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, filename)
                file.writeText(content)
                file.absolutePath
            }
        } catch (e: Exception) {
            fallbackSave(context, "ooxoo_log_${System.currentTimeMillis()}.txt", getAsText())
        }
    }

    private fun fallbackSave(context: Context, filename: String, content: String): String? {
        return try {
            val dir = context.getExternalFilesDir("logs") ?: return null
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, filename)
            file.writeText(content)
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
