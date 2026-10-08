package com.farhan.jarvis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CallLogReader {
    fun recent(context: Context, limit: Int = 15): String {
        if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return "Call log permission not granted. Ask him to allow it in the Jarvis app."
        }
        val fmt = SimpleDateFormat("EEE dd MMM HH:mm", Locale.UK)
        val lines = mutableListOf<String>()
        val projection = arrayOf(
            CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
            CallLog.Calls.DATE, CallLog.Calls.DURATION,
        )
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI, projection, null, null, "${CallLog.Calls.DATE} DESC"
        )?.use { c ->
            while (c.moveToNext() && lines.size < limit) {
                val number = c.getString(0) ?: "unknown"
                val name = c.getString(1)
                val kind = when (c.getInt(2)) {
                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.MISSED_TYPE -> "MISSED"
                    CallLog.Calls.REJECTED_TYPE -> "rejected"
                    CallLog.Calls.BLOCKED_TYPE -> "blocked"
                    else -> "other"
                }
                val who = if (name.isNullOrBlank()) number else "$name ($number)"
                val secs = c.getLong(4)
                lines += "${fmt.format(Date(c.getLong(3)))}  $kind  $who  ${secs / 60}m${secs % 60}s"
            }
        }
        return if (lines.isEmpty()) "No calls in the log." else lines.joinToString("\n")
    }
}
