package com.shilapi.xcertplay.usbbt

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thread-safe file logger for USB Bluetooth diagnostics and lifecycle events.
 *
 * Directs entries to both `logs/bluetooth-diagnostic.log` and the active `logs/diplay.log`,
 * ensuring diagnostic reports exported from the UI retain full Bluetooth hardware and
 * connection evidence.
 */
object UsbBtLog {
    private const val MAX_LOG_SIZE = 512 * 1024L // 512 KB
    private val lock = Any()

    fun log(context: Context?, tag: String, message: String) {
        Log.i(tag, message)
        val targetContext = context ?: return
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val formatted = "$timestamp  [$tag] $message\n"

        synchronized(lock) {
            runCatching {
                val logDir = File(targetContext.filesDir, "logs")
                if (!logDir.exists()) logDir.mkdirs()

                // 1. Append to bluetooth-diagnostic.log
                val btFile = File(logDir, "bluetooth-diagnostic.log")
                if (btFile.length() > MAX_LOG_SIZE) {
                    val prev = File(logDir, "bluetooth-diagnostic-prev.log")
                    btFile.renameTo(prev)
                }
                btFile.appendText(formatted)

                // 2. Also append to active session diplay.log if present
                val diplayFile = File(logDir, "diplay.log")
                if (diplayFile.exists() && diplayFile.length() <= MAX_LOG_SIZE) {
                    diplayFile.appendText(formatted)
                }
            }
        }
    }

    fun warn(context: Context?, tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
            log(context, tag, "WARN: $message (${throwable.javaClass.simpleName}: ${throwable.message})")
        } else {
            Log.w(tag, message)
            log(context, tag, "WARN: $message")
        }
    }
}
