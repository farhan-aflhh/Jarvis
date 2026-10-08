package com.farhan.jarvis

import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the brain running in Termux on this same phone. */
object Brain {
    private const val BASE = "http://127.0.0.1:8765"

    data class Answer(val reply: String, val report: String)

    private class WrongCode : Exception()

    fun ask(prefs: Prefs, text: String, calls: String?): Answer {
        val body = JSONObject()
            .put("text", text)
            .put("ics_url", prefs.icsUrl)
        if (calls != null) body.put("calls", calls)
        return try {
            val json = post("/ask", prefs.brainCode, body, 900_000)
            Answer(json.optString("reply", "Done, sir."), json.optString("report", ""))
        } catch (e: ConnectException) {
            Answer("My brain isn't running, sir. Open Termux and type jarvis.", "")
        } catch (e: WrongCode) {
            Answer("The brain code doesn't match, sir. Check it in settings.", "")
        } catch (e: Exception) {
            Answer("I've lost the uplink, sir. ${e.message ?: ""}".take(160), "")
        }
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
