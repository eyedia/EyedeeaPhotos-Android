package com.eyediatech.eyedeeaphotos.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {
    private const val LOG_FILE_NAME = "eyedeea_debug_logs.txt"
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        val cacheDir = context.cacheDir
        logFile = File(cacheDir, LOG_FILE_NAME)
        // Clear log file if it exceeds 5MB
        if (logFile?.exists() == true && logFile!!.length() > 5 * 1024 * 1024) {
            logFile?.delete()
        }
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        writeToFile("DEBUG", tag, message)
    }

    fun e(tag: String, message: String, tr: Throwable? = null) {
        if (tr != null) {
            Log.e(tag, message, tr)
            writeToFile("ERROR", tag, "$message\n${android.util.Log.getStackTraceString(tr)}")
        } else {
            Log.e(tag, message)
            writeToFile("ERROR", tag, message)
        }
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        writeToFile("WARN", tag, message)
    }

    private fun writeToFile(level: String, tag: String, message: String) {
        val file = logFile ?: return
        try {
            val timestamp = dateFormat.format(Date())
            val logLine = "$timestamp $level/$tag: $message\n"
            FileWriter(file, true).use {
                it.append(logLine)
            }
        } catch (e: Exception) {
            Log.e("FileLogger", "Failed to write log", e)
        }
    }

    fun getLogFile(context: Context): File {
        if (logFile == null) {
            init(context)
        }
        return logFile!!
    }
}
