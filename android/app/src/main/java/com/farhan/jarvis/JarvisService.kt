package com.farhan.jarvis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Keeps Jarvis alive in the background: listens for "Jarvis", hears the request,
 * asks the brain, and speaks the answer.
 */
class JarvisService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_LISTEN = "listen"
        const val ACTION_ASK = "ask"
        const val ACTION_RELOAD = "reload"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL = "jarvis"
        private const val NOTIFICATION_ID = 7
        // Keep in step with ACKS in brain/server.py, which pre-records these in his voice.
        private val ACKS = listOf(
            "Right away, sir.", "On it.", "Leave it with me.", "One moment, sir.",
            "Consider it handled.", "Very good, sir.", "Allow me.",
        )
        private val CALL_WORDS = Regex("\\b(call|calls|called|calling|missed|rang|ring|phone|dial)", RegexOption.IGNORE_CASE)

        fun send(context: Context, action: String, text: String? = null) {
            val i = Intent(context, JarvisService::class.java).setAction(action)
            if (text != null) i.putExtra(EXTRA_TEXT, text)
            ContextCompat.startForegroundService(context, i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val voiceExec = Executors.newSingleThreadExecutor() // fetches his voice, in order
    private lateinit var prefs: Prefs
    private var porcupine: PorcupineManager? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var busy = false
    private var tone: ToneGenerator? = null

    private class Line(val id: String, val text: String, val audio: ByteArray?)
    private val queue = ArrayDeque<Line>()
    private var playing = false
    private var player: MediaPlayer? = null
    private var fallbackLine: Line? = null
    private var fileCounter = 0

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 70) } catch (e: RuntimeException) { null }
        tts = TextToSpeech(this, this)
        JarvisState.update { running = true }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LISTEN -> beginListening()
            ACTION_ASK -> intent.getStringExtra(EXTRA_TEXT)?.let { handle(it) }
            ACTION_RELOAD -> {
                releaseWakeWord()
                wakeOn()
            }
            else -> wakeOn() // ACTION_START, or Android restarting us
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------- foreground
    private fun goForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Jarvis is standing by")
            .setContentText("Say \"Jarvis\"")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        return try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
            true
        } catch (e: Exception) {
            setStatus("Couldn't start in the background. Open Jarvis and tap Activate.")
            false
        }
    }

    // ---------------------------------------------------------------- wake word
    private fun ensureWakeWord(): Boolean {
        if (porcupine != null) return true
        val key = prefs.accessKey
        if (key.isBlank()) {
            setStatus("Ready. Add your Picovoice AccessKey in settings for the wake word.")
            return false
        }
        return try {
            porcupine = PorcupineManager.Builder()
                .setAccessKey(key)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .setSensitivity(0.6f)
                .setErrorCallback { e -> setStatus("Wake word error: ${e.message}") }
                .build(applicationContext) { _ -> main.post { onWake() } }
            true
        } catch (e: PorcupineException) {
            setStatus("Wake word couldn't start: ${e.message}")
            false
        }
    }

    private fun wakeOn() {
        if (busy) return
        if (!ensureWakeWord()) return
        try {
            porcupine?.start()
            setStatus("Standing by. Say \"Jarvis\".")
        } catch (e: PorcupineException) {
            setStatus("Wake word couldn't start: ${e.message}")
        }
    }

    private fun wakeOff() {
        try {
            porcupine?.stop()
        } catch (_: PorcupineException) {
        }
    }

    private fun releaseWakeWord() {
        wakeOff()
        porcupine?.delete()
        porcupine = null
    }

    private fun onWake() {
        if (!busy) beginListening()
    }

    // ---------------------------------------------------------------- listening
    private fun beginListening() {
        busy = true
        wakeOff()
        stopSpeaking()
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 150)
        setStatus("Listening…")
        main.postDelayed({ startRecognizer() }, 300)
    }

    private fun startRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            finishWith("Speech recognition isn't available on this phone, sir. Install the Google app.")
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) finishWith("I didn't quite catch that, sir.") else handle(text)
            }

            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        finishWith("I didn't quite catch that, sir.")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        finishWith("No signal for my ears, sir. Check the internet.")
                    else -> finishWith("My hearing's gone fuzzy, sir. Error $error.")
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { setStatus("Thinking…") }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        r.startListening(intent)
    }

    // ---------------------------------------------------------------- thinking
    private fun handle(text: String) {
        busy = true
        wakeOff()
        JarvisState.update { heard = text; reply = "…" }
        setStatus("Working on it…")
        say(ACKS.random(), "ack")
        worker.execute {
            val calls = if (CALL_WORDS.containsMatchIn(text)) CallLogReader.recent(this) else null
            val answer = Brain.ask(prefs, text, calls)
            JarvisState.update {
                reply = answer.reply
                if (answer.report.isNotBlank()) report = answer.report
            }
            say(answer.reply, "reply", answer.audio, fetch = false)
        }
    }

    private fun finishWith(message: String) {
        JarvisState.update { reply = message }
        say(message, "reply")
    }

    // ---------------------------------------------------------------- speaking
    // His real voice comes from the brain (a natural British male neural voice).
    // If that can't be reached, the phone's own text-to-speech steps in, set to a male voice.

    /** Queue a line. Lines always play in the order they were asked for. */
    private fun say(text: String, id: String, audio: ByteArray? = null, fetch: Boolean = true) {
        voiceExec.execute {
            val sound = audio ?: if (fetch) Brain.say(prefs, text) else null
            main.post {
                queue.addLast(Line(id, text, sound))
                if (!playing) playNext()
            }
        }
    }

    private fun playNext() {
        val line = queue.removeFirstOrNull()
        if (line == null) {
            playing = false
            return
        }
        playing = true
        if (line.id == "reply") setStatus("Speaking…")
        if (line.audio == null || !playAudio(line)) speakFallback(line)
    }

    private fun playAudio(line: Line): Boolean {
        var mp: MediaPlayer? = null
        return try {
            val file = File(cacheDir, "line_${fileCounter++ % 4}.mp3")
            file.writeBytes(line.audio!!)
            mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.path)
            mp.setOnCompletionListener {
                it.release()
                if (player === it) player = null
                lineDone(line)
            }
            mp.setOnErrorListener { m, _, _ ->
                m.release()
                if (player === m) player = null
                speakFallback(line)
                true
            }
            mp.prepare()
            mp.start()
            player = mp
            true
        } catch (e: Exception) {
            mp?.release()
            false
        }
    }

    private fun speakFallback(line: Line) {
        val t = tts
        if (!ttsReady || t == null) {
            lineDone(line)
            return
        }
        fallbackLine = line
        t.speak(line.text, TextToSpeech.QUEUE_FLUSH, null, "fallback")
    }

    private fun lineDone(line: Line) {
        playNext()
        if (line.id == "reply" && !playing) doneSpeaking()
    }

    private fun stopSpeaking() {
        queue.clear()
        player?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        player = null
        fallbackLine = null
        tts?.stop()
        playing = false
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        t.language = Locale.UK
        val male = pickMaleVoice(t)
        if (male != null) t.voice = male
        t.setPitch(if (male != null) 0.95f else 0.8f) // no male voice installed: deepen what there is
        t.setSpeechRate(0.95f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()
            private fun finished() {
                main.post {
                    val line = fallbackLine ?: return@post
                    fallbackLine = null
                    lineDone(line)
                }
            }
        })
        ttsReady = true
    }

    private fun pickMaleVoice(t: TextToSpeech): android.speech.tts.Voice? {
        val voices = try { t.voices.orEmpty() } catch (e: Exception) { emptySet() }
        val english = voices.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
        fun isMale(v: android.speech.tts.Voice): Boolean {
            val n = v.name.lowercase()
            if ("female" in n) return false
            return listOf("male", "gbd", "gbb", "rjs", "smtm", "-iom", "-iob").any { it in n }
        }
        return english.firstOrNull { it.locale.country == "GB" && isMale(it) }
            ?: english.firstOrNull { isMale(it) }
    }

    private fun doneSpeaking() {
        busy = false
        wakeOn()
    }

    private fun setStatus(s: String) = JarvisState.update { status = s }

    // ---------------------------------------------------------------- shutdown
    override fun onDestroy() {
        releaseWakeWord()
        recognizer?.destroy()
        stopSpeaking()
        tts?.shutdown()
        tone?.release()
        worker.shutdownNow()
        voiceExec.shutdownNow()
        JarvisState.update { running = false; status = "Offline" }
        super.onDestroy()
    }
}
