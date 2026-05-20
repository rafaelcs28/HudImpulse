package com.hudimpulse.app

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Forwards log lines to ntfy.sh (free push service) so they can be
 * monitored in real time on any device with internet access.
 *
 * Topic: hud-impulse-raf28  (obscure enough to avoid noise)
 * Monitor on Mac Mini: bash tools/watch_logs.sh
 */
object LogForwarder {

    private const val NTFY_URL = "https://ntfy.sh/hud-impulse-raf28"

    fun i(tag: String, msg: String) { Log.i(tag, msg); send("I/$tag: $msg") }
    fun w(tag: String, msg: String) { Log.w(tag, msg); send("W/$tag: $msg") }
    fun e(tag: String, msg: String) { Log.e(tag, msg); send("E/$tag: $msg") }
    fun d(tag: String, msg: String) { Log.d(tag, msg); send("D/$tag: $msg") }

    private fun send(line: String) {
        Thread {
            try {
                val conn = URL(NTFY_URL).openConnection() as HttpURLConnection
                conn.requestMethod  = "POST"
                conn.doOutput       = true
                conn.connectTimeout = 4_000
                conn.readTimeout    = 4_000
                val bytes = line.toByteArray()
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }
}
