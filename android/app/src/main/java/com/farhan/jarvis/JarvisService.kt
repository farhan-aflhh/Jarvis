package com.farhan.jarvis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
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
 * Keeps Jarvis alive in the background and runs the conversation:
 * hears "Jarvis", listens, asks the brain, speaks the answer sentence by sentence,
 * then keeps listening for a follow-up. Saying "Jarvis" while he talks interrupts him.
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
        private val FILLERS = listOf(
            "Mm, one moment.", "Let me see.", "Right, leave it with me.", "Bear with me, sir.",
            "On it.", "Ah, let me check.", "One moment, sir.", "Allow me.",
            "Hmm.", "Right.", "Mm, let me think.", "Good question.",
        )
        private val STILL_WORKING = listOf("Still on it, sir.", "Nearly there, sir.")
        private val SIGN_OFFS = listOf("My pleasure, sir.", "Always, sir.", "Very good, sir.", "Any time, sir.")
        private const val NOT_CAUGHT = "I didn't quite catch that, sir."

        private val CALL_WORDS = Regex("\\b(call|calls|called|calling|missed|rang|ring|phone|dial)", RegexOption.IGNORE_CASE)
        private val WRAP_UP = Regex(
            "^(ok(ay)?,? |alright,? )?(thanks|thank you|thank you so much|cheers|that's all|that's it|that'll be all|" +
                "nothing|nothing else|no|nope|no thanks|no thank you|bye|goodbye|good night|stop|never mind|" +
                "all good|i'm good|we're done|done)( jarvis| sir| mate)?[.!]*$",
            RegexOption.IGNORE_CASE,
        )
        private const val STILL_WORKING_AFTER_MS = 14_000L

        fun send(context: Context, action: String, text: String? = null) {
            val i = Intent(context, JarvisService::class.java).setAction(action)
            if (text != null) i.putExtra(EXTRA_TEXT, text)
            ContextCompat.startForegroundService(context, i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val voiceExec = Executors.newSingleThreadExecutor() // gets his voice, strictly in order
    private lateinit var prefs: Prefs
    private var porcupine: PorcupineManager? = null
    private var wakeActive = false
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var chimes: SoundPool? = null
    private var chimeWake = 0
    private var chimeListen = 0

    // Conversation state
    @Volatile private var turn = 0               // bumps on every new request or interruption; stale audio is dropped
    private var busy = false           // listening, thinking or speaking
    private var followUp = false       // listening for a reply without the wake word
    private var replyStarted = false   // the current answer has begun playing
    private var lastFiller = ""

    // Speech queue
    private class Line(val kind: Kind, val text: String, val audio: ByteArray?, val turn: Int, val keepTalking: Boolean = true)
    private enum class Kind { FILLER, SENTENCE, END }
    private val queue = ArrayDeque<Line>()
    private var playing = false
    private var player: MediaPlayer? = null
    private var fallbackLine: Line? = null
    private var fileCounter = 0

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        chimes = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build().also {
                chimeWake = it.load(this, R.raw.chime_wake, 1)
                chimeListen = it.load(this, R.raw.chime_listen, 1)
            }
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
            ACTION_LISTEN -> { interrupt(); beginListening(followUp = false) }
            ACTION_ASK -> intent.getStringExtra(EXTRA_TEXT)?.let { interrupt(); handle(it) }
            ACTION_RELOAD -> {
                releaseWakeWord()
                if (!busy) standBy()
            }
            else -> if (!busy) standBy() // ACTION_START, or Android restarting us
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

    private fun wakeOn(): Boolean {
        if (wakeActive) return true
        if (!ensureWakeWord()) return false
        return try {
            porcupine?.start()
            wakeActive = true
            true
        } catch (e: PorcupineException) {
            setStatus("Wake word couldn't start: ${e.message}")
            false
        }
    }

    private fun wakeOff() {
        if (!wakeActive) return
        try {
            porcupine?.stop()
        } catch (_: PorcupineException) {
        }
        wakeActive = false
    }

    private fun releaseWakeWord() {
        wakeOff()
        porcupine?.delete()
        porcupine = null
    }

    /** Back to waiting for "Jarvis". */
    private fun standBy() {
        busy = false
        followUp = false
        if (wakeOn()) setStatus("Standing by. Say \"Jarvis\".")
    }

    private fun onWake() {
        // Heard "Jarvis": either a fresh request, or he's interrupting me mid-sentence.
        interrupt()
        beginListening(followUp = false)
    }

    /** Stop whatever I'm saying or working on, and drop anything still on its way. */
    private fun interrupt() {
        turn++
        Brain.cancel()
        stopSpeaking()
        recognizer?.cancel()
    }

    // ---------------------------------------------------------------- listening
    private fun beginListening(followUp: Boolean) {
        busy = true
        this.followUp = followUp
        wakeOff() // the mic can only be used by one listener at a time
        if (followUp) {
            chimes?.play(chimeListen, 1f, 1f, 1, 0, 1f)
            setStatus("Listening… (just reply, no need to say Jarvis)")
        } else {
            chimes?.play(chimeWake, 1f, 1f, 1, 0, 1f)
            setStatus("Listening…")
        }
        val myTurn = turn
        main.postDelayed({ if (myTurn == turn) startRecognizer() }, 250)
    }

    private fun startRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            finishWith("Speech recognition isn't available on this phone, sir. Install the Google app.")
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        val myTurn = turn
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                if (myTurn != turn) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                when {
                    text.isNotBlank() -> handle(text, inConversation = followUp)
                    followUp -> standBy()
                    else -> finishWith(NOT_CAUGHT)
                }
            }

            override fun onError(error: Int) {
                if (myTurn != turn) return
                if (followUp) {
                    standBy() // silence after an answer just means the conversation's over
                    return
                }
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finishWith(NOT_CAUGHT)
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        finishWith("No signal for my ears, sir. Check the internet.")
                    SpeechRecognizer.ERROR_CLIENT -> standBy()
                    else -> finishWith("My hearing's gone a bit fuzzy, sir. Do try again.")
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { setStatus("Listening…") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { setStatus("Thinking…") }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!partial.isNullOrBlank() && myTurn == turn) JarvisState.update { heard = partial }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        r.startListening(intent)
    }

    // ---------------------------------------------------------------- thinking
    private fun handle(text: String, inConversation: Boolean = false) {
        turn++
        val myTurn = turn
        busy = true
        followUp = false
        replyStarted = false
        wakeOff()
        JarvisState.update { heard = text; reply = "…" }

        // "Thanks", "that's all": a quick sign-off, then back to standing by.
        if (WRAP_UP.matches(text.trim())) {
            val bye = SIGN_OFFS.random()
            JarvisState.update { reply = bye }
            say(Kind.SENTENCE, bye)
            say(Kind.END, "", keepTalking = false)
            return
        }

        setStatus("Thinking… (say \"Jarvis\" to cancel)")
        wakeOn() // he can still cut in with "Jarvis" while I think
        // Mid-conversation, a person doesn't always say "hmm" first. Sometimes just answer.
        if (!inConversation || Math.random() < 0.5) say(Kind.FILLER, pickFiller())
        main.postDelayed({
            if (myTurn == turn && !replyStarted) say(Kind.FILLER, STILL_WORKING.random())
        }, STILL_WORKING_AFTER_MS)

        worker.execute {
            if (myTurn != turn) return@execute
            val calls = if (CALL_WORDS.containsMatchIn(text)) CallLogReader.recent(this) else null
            var shown = ""
            val answer = Brain.askStream(prefs, text, calls) { sentence, audio ->
                if (myTurn == turn) {
                    shown = if (shown.isEmpty()) sentence else "$shown $sentence"
                    val soFar = shown
                    JarvisState.update { reply = soFar }
                    say(Kind.SENTENCE, sentence, audio, fetch = false, forTurn = myTurn)
                }
            }
            if (answer.cancelled || myTurn != turn) return@execute
            if (answer.report.isNotBlank()) JarvisState.update { report = answer.report }
            if (!answer.spoken) {
                JarvisState.update { reply = answer.reply }
                say(Kind.SENTENCE, answer.reply, answer.audio, fetch = answer.audio == null, forTurn = myTurn)
            } else if (answer.reply.isNotBlank()) {
                JarvisState.update { reply = answer.reply }
            }
            say(Kind.END, "", forTurn = myTurn)
        }
    }

    private fun pickFiller(): String {
        val f = FILLERS.filter { it != lastFiller }.random()
        lastFiller = f
        return f
    }

    /** Say something that isn't an answer (errors), then stand by. */
    private fun finishWith(message: String) {
        turn++
        busy = true
        replyStarted = false
        JarvisState.update { reply = message }
        say(Kind.SENTENCE, message)
        say(Kind.END, "", keepTalking = false)
    }

    // ---------------------------------------------------------------- speaking
    // His real voice comes from the brain (a natural British male neural voice).
    // If that can't be reached, the phone's own text-to-speech steps in, set to a male voice.

    /** Queue a line for the current turn. Lines always play in the order they were asked for. */
    private fun say(
        kind: Kind,
        text: String,
        audio: ByteArray? = null,
        fetch: Boolean = true,
        keepTalking: Boolean = true,
        forTurn: Int = turn,
    ) {
        voiceExec.execute {
            if (forTurn != turn) return@execute
            val sound = if (kind == Kind.END) null else audio ?: if (fetch) Brain.say(prefs, text) else null
            main.post {
                if (forTurn != turn) return@post
                queue.addLast(Line(kind, text, sound, forTurn, keepTalking))
                if (!playing) playNext()
            }
        }
    }

    private fun playNext() {
        while (true) {
            val line = queue.removeFirstOrNull()
            if (line == null) {
                playing = false
                return
            }
            if (line.turn != turn) continue
            if (line.kind == Kind.END) {
                playing = false
                turnFinished(line)
                return
            }
            if (line.kind == Kind.FILLER && replyStarted) continue // the answer's here; skip late fillers
            playing = true
            if (line.kind == Kind.SENTENCE && !replyStarted) {
                replyStarted = true
                setStatus("Speaking… (say \"Jarvis\" to interrupt)")
                wakeOn() // listen for "Jarvis" while I talk, so he can cut in
            }
            if (line.audio == null || !playAudio(line)) speakFallback(line)
            return
        }
    }

    private fun turnFinished(line: Line) {
        wakeOff()
        if (line.keepTalking) {
            // Keep the conversation going: listen for a reply without the wake word.
            beginListening(followUp = true)
        } else {
            standBy()
        }
    }

    private fun playAudio(line: Line): Boolean {
        var mp: MediaPlayer? = null
        return try {
            val file = File(cacheDir, "line_${fileCounter++ % 6}.mp3")
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
                if (player === it) {
                    player = null
                    playNext()
                }
            }
            mp.setOnErrorListener { m, _, _ ->
                m.release()
                if (player === m) {
                    player = null
                    speakFallback(line)
                }
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
            playNext()
            return
        }
        fallbackLine = line
        t.speak(line.text, TextToSpeech.QUEUE_FLUSH, null, "fallback")
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
                    if (fallbackLine == null) return@post
                    fallbackLine = null
                    playNext()
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

    private fun setStatus(s: String) = JarvisState.update { status = s }

    // ---------------------------------------------------------------- shutdown
    override fun onDestroy() {
        turn++
        Brain.cancel()
        releaseWakeWord()
        recognizer?.destroy()
        stopSpeaking()
        tts?.shutdown()
        chimes?.release()
        worker.shutdownNow()
        voiceExec.shutdownNow()
        JarvisState.update { running = false; status = "Offline" }
        super.onDestroy()
    }
}
