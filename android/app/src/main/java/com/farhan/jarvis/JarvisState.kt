package com.farhan.jarvis

import android.os.Handler
import android.os.Looper

/** What the screen shows. The service writes, the activity reads. */
object JarvisState {
    var running = false
    var status = "Offline"
    var heard = ""
    var reply = ""
    var report = ""
    var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    fun update(block: JarvisState.() -> Unit) {
        main.post {
            block()
            listener?.invoke()
        }
    }
}
