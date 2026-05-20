package com.hudimpulse.app

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends log lines to the remote log server (tools/log_server.py on Mac Mini).
 * Configured via BuildConfig.LOG_SERVER_URL (local.properties → log.server.url).
 * Silent no-op when URL is empty.
 */
object LogForwarder {

    private val url = BuildConfig.LOG_SERVER_URL.takeIf { it.isNotEmpty() }

    fun i(tag: String, msg: String) { Log.i(tag, msg); send("I/$tag: $msg") }
    fun w(tag: String, msg: String) { Log.w(tag, msg); send("W/$tag: $msg") }
    fun e(tag: String, msg: String) { Log.e(tag, msg); send("E/$tag: $msg") }
    fun d(tag: String, msg: String) { Log.d(tag, msg); send("D/$tag: $msg") }

    private fun send(line: String) {
        val target = url ?: return
        Thread {
            try {
                val conn = URL(target).openConnection() as HttpURLConnection
                conn.requestMethod  = "POST"
                conn.doOutput       = true
                conn.connectTimeout = 2_000
                conn.readTimeout    = 2_000
                val bytes = line.toByteArray()
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }
}
