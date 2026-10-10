package com.farhan.jarvis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat

/** Saves Jarvis's PDFs to Downloads/Jarvis and lets you open or share them. */
object Reports {
    private const val CHANNEL = "jarvis_reports"
    private var nextId = 100

    /** Returns where the PDF was saved, or null if this phone can't save it. */
    fun save(context: Context, name: String, bytes: ByteArray): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Jarvis")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("no stream")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    fun openIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun shareIntent(uri: Uri, title: String): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share PDF",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notifyReady(context: Context, uri: Uri, title: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reports and PDFs", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val id = nextId++
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(context, id, openIntent(uri), flags)
        val share = PendingIntent.getActivity(context, id + 10_000, shareIntent(uri, title), flags)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Your PDF is ready, sir")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\nSaved in Downloads › Jarvis"))
            .setContentIntent(open)
            .addAction(0, "Open", open)
            .addAction(0, "Share", share)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(id, n)
        } catch (e: SecurityException) {
            // Notifications not allowed; the PDF is still saved and shown in the app.
        }
    }

    /** The report's title: its first "# " heading. */
    fun titleOf(markdown: String): String =
        markdown.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
            ?: "Jarvis report"
}
