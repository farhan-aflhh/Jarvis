package com.farhan.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var heard: TextView
    private lateinit var reply: TextView
    private lateinit var reportLabel: TextView
    private lateinit var report: TextView
    private lateinit var pdfRow: View
    private lateinit var input: EditText
    private var pending: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)
        heard = findViewById(R.id.heard)
        reply = findViewById(R.id.reply)
        reportLabel = findViewById(R.id.reportLabel)
        report = findViewById(R.id.report)
        pdfRow = findViewById(R.id.pdfRow)
        findViewById<Button>(R.id.openPdf).setOnClickListener {
            JarvisState.pdfUri?.let { uri ->
                try {
                    startActivity(Reports.openIntent(uri))
                } catch (e: Exception) {
                    toast("No PDF viewer found. It's saved in Downloads › Jarvis.")
                }
            }
        }
        findViewById<Button>(R.id.sharePdf).setOnClickListener {
            JarvisState.pdfUri?.let { uri -> startActivity(Reports.shareIntent(uri, JarvisState.pdfTitle)) }
        }
        input = findViewById(R.id.input)

        val brainCode = findViewById<EditText>(R.id.brainCode)
        val icsUrl = findViewById<EditText>(R.id.icsUrl)
        val settings = findViewById<View>(R.id.settings)
        val settingsToggle = findViewById<TextView>(R.id.settingsToggle)
        brainCode.setText(prefs.brainCode)
        icsUrl.setText(prefs.icsUrl)

        // First run: open settings so the keys get filled in.
        if (prefs.brainCode.isBlank()) {
            settings.visibility = View.VISIBLE
            settingsToggle.text = "SETTINGS  ▴"
        }

        toggle.setOnClickListener {
            if (JarvisState.running) JarvisService.send(this, JarvisService.ACTION_STOP)
            else withPermissions { JarvisService.send(this, JarvisService.ACTION_START) }
        }
        findViewById<Button>(R.id.speak).setOnClickListener {
            withPermissions { JarvisService.send(this, JarvisService.ACTION_LISTEN) }
        }
        findViewById<Button>(R.id.send).setOnClickListener { sendTyped() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
        }
        findViewById<Button>(R.id.reset).setOnClickListener {
            Thread {
                val ok = Brain.reset(prefs)
                runOnUiThread {
                    toast(if (ok) "Clean slate, sir." else "Couldn't reach the brain. Is Termux running jarvis?")
                }
            }.start()
        }
        settingsToggle.setOnClickListener {
            val show = settings.visibility != View.VISIBLE
            settings.visibility = if (show) View.VISIBLE else View.GONE
            settingsToggle.text = if (show) "SETTINGS  ▴" else "SETTINGS  ▾"
        }
        findViewById<Button>(R.id.save).setOnClickListener {
            prefs.brainCode = brainCode.text.toString()
            prefs.icsUrl = icsUrl.text.toString()
            if (JarvisState.running) JarvisService.send(this, JarvisService.ACTION_RELOAD)
            toast("Saved.")
        }
        findViewById<Button>(R.id.battery).setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        JarvisState.listener = { render() }
        render()
    }

    override fun onPause() {
        JarvisState.listener = null
        super.onPause()
    }

    private fun render() {
        status.text = JarvisState.status
        toggle.text = if (JarvisState.running) "STAND\nDOWN" else "ACTIVATE"
        toggle.isActivated = JarvisState.running
        toggle.setTextColor(getColor(if (JarvisState.running) R.color.bg else R.color.gold))
        if (JarvisState.heard.isNotBlank()) heard.text = JarvisState.heard
        if (JarvisState.reply.isNotBlank()) reply.text = JarvisState.reply
        val hasReport = JarvisState.report.isNotBlank()
        reportLabel.visibility = if (hasReport) View.VISIBLE else View.GONE
        report.visibility = if (hasReport) View.VISIBLE else View.GONE
        report.text = JarvisState.report
        pdfRow.visibility = if (JarvisState.pdfUri != null) View.VISIBLE else View.GONE
    }

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        withPermissions { JarvisService.send(this, JarvisService.ACTION_ASK, text) }
    }

    // ---------------------------------------------------------------- permissions
    private fun withPermissions(action: () -> Unit) {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CALL_LOG)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            action()
        } else {
            pending = action
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val action = pending
        pending = null
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action?.invoke()
        } else {
            toast("Jarvis needs the microphone to hear you.")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
