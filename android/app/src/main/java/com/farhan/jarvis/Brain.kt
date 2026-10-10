package com.farhan.jarvis

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the brain running in Termux on this same phone. */
object Brain {
    private const val BASE = "http://127.0.0.1:8765"

    /** audio is his recorded voice (mp3), or null to fall back to the phone's own voice. */
    data class Answer(
        val reply: String,
        val report: String,
        val audio: ByteArray? = null,
        val spoken: Boolean = false,    // true when the sentences were already handed over to be spoken
        val cancelled: Boolean = false, // true when he was interrupted
        val pdf: String? = null,        // file name of a PDF the brain made
    )

    @Volatile private var live: HttpURLConnection? = null
    @Volatile private var cancelled = false

    /** Stop the answer in progress (he was interrupted). */
    fun cancel() {
        cancelled = true
        live?.let { c -> Thread { c.disconnect() }.start() }
    }

    /**
     * Ask, and hand over each sentence (with his voice) the moment it's ready.
     * Falls back to the whole-answer call if the brain is an older version.
     */
    fun askStream(prefs: Prefs, text: String, calls: String?, onSentence: (String, ByteArray?) -> Unit): Answer {
        cancelled = false
        val body = JSONObject().put("text", text).put("ics_url", prefs.icsUrl)
        if (calls != null) body.put("calls", calls)
        val c = URL("$BASE/ask_stream").openConnection() as HttpURLConnection
        live = c
        return try {
            c.requestMethod = "POST"
            c.connectTimeout = 4_000
            c.readTimeout = 900_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-Jarvis-Code", prefs.brainCode)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            when (val status = c.responseCode) {
                401 -> return Answer("The brain code doesn't match, sir. Check it in settings.", "")
                404 -> return ask(prefs, text, calls).also { onSentence(it.reply, it.audio) }.copy(spoken = true)
                !in 200..299 -> return Answer("I've lost the uplink, sir. Error $status.", "")
            }
            var reply = ""
            var report = ""
            var pdf: String? = null
            var any = false
            val reader = c.inputStream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val j = JSONObject(line)
                if (j.has("say")) {
                    any = true
                    onSentence(j.getString("say"), decode(j))
                }
                if (j.optBoolean("done")) {
                    reply = j.optString("reply", "")
                    report = j.optString("report", "")
                    pdf = j.optString("pdf", "").takeIf { it.isNotBlank() && it != "null" }
                }
            }
            if (cancelled) Answer("", "", cancelled = true) else Answer(reply, report, spoken = any, pdf = pdf)
        } catch (e: ConnectException) {
            Answer("My brain isn't running, sir. Open Termux and type jarvis.", "")
        } catch (e: Exception) {
            if (cancelled) Answer("", "", cancelled = true)
            else Answer("I've lost the uplink, sir. ${e.message ?: ""}".take(160), "")
        } finally {
            c.disconnect()
            if (live === c) live = null
        }
    }

    private class WrongCode : Exception()

    fun ask(prefs: Prefs, text: String, calls: String?): Answer {
        val body = JSONObject()
            .put("text", text)
            .put("ics_url", prefs.icsUrl)
        if (calls != null) body.put("calls", calls)
        return try {
            val json = post("/ask", prefs.brainCode, body, 900_000)
            Answer(
                json.optString("reply", "Done, sir."), json.optString("report", ""), decode(json),
                pdf = json.optString("pdf", "").takeIf { it.isNotBlank() && it != "null" },
            )
        } catch (e: ConnectException) {
            Answer("My brain isn't running, sir. Open Termux and type jarvis.", "")
        } catch (e: WrongCode) {
            Answer("The brain code doesn't match, sir. Check it in settings.", "")
        } catch (e: Exception) {
            Answer("I've lost the uplink, sir. ${e.message ?: ""}".take(160), "")
        }
    }

    /** Fetch a PDF the brain made. */
    fun download(prefs: Prefs, name: String): ByteArray? = try {
        val c = URL("$BASE/file/" + java.net.URLEncoder.encode(name, "UTF-8")).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 4_000
            c.readTimeout = 60_000
            c.setRequestProperty("X-Jarvis-Code", prefs.brainCode)
            if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /** His voice for a line of text, or null if the brain or voice service can't be reached. */
    fun say(prefs: Prefs, text: String): ByteArray? = try {
        decode(post("/say", prefs.brainCode, JSONObject().put("text", text), 45_000))
    } catch (e: Exception) {
        null
    }

    private fun decode(json: JSONObject): ByteArray? {
        val b64 = json.optString("audio", "")
        if (b64.isBlank() || b64 == "null") return null
        return try { Base64.decode(b64, Base64.DEFAULT) } catch (e: IllegalArgumentException) { null }
    }

    fun reset(prefs: Prefs): Boolean = try {
        post("/reset", prefs.brainCode, JSONObject(), 10_000)
        true
    } catch (e: Exception) {
        false
    }

    private fun post(path: String, code: String, body: JSONObject, readTimeout: Int): JSONObject {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 4_000
            c.readTimeout = readTimeout
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-Jarvis-Code", code)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = c.responseCode
            if (status == 401) throw WrongCode()
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            if (status !in 200..299) throw IOException("HTTP $status")
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }
}
