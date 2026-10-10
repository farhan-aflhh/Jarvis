package com.farhan.jarvis

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.nio.FloatBuffer

/**
 * Listens for "Hey Jarvis" entirely on the phone, using the free openWakeWord models
 * (github.com/dscripka/openWakeWord). Nothing leaves the phone.
 *
 * Pipeline, every 80 ms of audio: melspectrogram -> speech embedding -> wake word score.
 * A line-for-line port of a Python version checked against the official openWakeWord library.
 */
class WakeWord(context: Context, private val onWake: () -> Unit) {

    companion object {
        private const val RATE = 16_000
        private const val CHUNK = 1280        // 80 ms
        private const val CONTEXT = 480       // 3 extra hops so mel frames line up across chunks
        private const val MEL_BINS = 32
        private const val MEL_WINDOW = 76     // mel frames per embedding
        private const val EMBEDDING = 96
        private const val FEATURES = 16       // embeddings per decision
        private const val THRESHOLD = 0.5f
        private const val COOLDOWN_CHUNKS = 25 // ~2 s before it can fire again
    }

    private val env = OrtEnvironment.getEnvironment()
    private val melModel: OrtSession
    private val embModel: OrtSession
    private val wakeModel: OrtSession

    init {
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
        fun load(name: String) = env.createSession(context.assets.open(name).use { it.readBytes() }, opts)
        melModel = load("melspectrogram.onnx")
        embModel = load("embedding_model.onnx")
        wakeModel = load("hey_jarvis_v0.1.onnx")
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    // Rolling state
    private var raw = FloatArray(CONTEXT)
    private val mel = ArrayDeque<FloatArray>()
    private val features = ArrayDeque<FloatArray>()

    val isRunning get() = running

    /** Start listening. Returns false if the microphone can't be opened. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, CHUNK * 2 * 4),
            )
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            return false
        }
        reset()
        running = true
        thread = Thread({ loop(rec) }, "jarvis-wake").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(600)
        thread = null
    }

    fun release() {
        stop()
        melModel.close()
        embModel.close()
        wakeModel.close()
    }

    private fun loop(rec: AudioRecord) {
        val pcm = ShortArray(CHUNK)
        var cooldown = 0
        try {
            while (running) {
                var got = 0
                while (got < CHUNK && running) {
                    val n = rec.read(pcm, got, CHUNK - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < CHUNK) continue
                val score = process(pcm)
                if (cooldown > 0) {
                    cooldown--
                } else if (score >= THRESHOLD) {
                    cooldown = COOLDOWN_CHUNKS
                    onWake()
                }
            }
        } catch (e: Exception) {
            Log.e("JarvisWake", "wake word loop stopped", e)
        } finally {
            running = false
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    private fun reset() {
        raw = FloatArray(CONTEXT)
        mel.clear()
        repeat(MEL_WINDOW) { mel.addLast(FloatArray(MEL_BINS) { 1f }) }
        features.clear()
    }

    /** One 80 ms chunk in, wake word score (0..1) out. */
    private fun process(chunk: ShortArray): Float {
        // 1. Melspectrogram of the last 80 ms plus a little context
        val audio = FloatArray(CONTEXT + CHUNK)
        System.arraycopy(raw, 0, audio, 0, CONTEXT)
        for (i in 0 until CHUNK) audio[CONTEXT + i] = chunk[i].toFloat()
        raw = audio.copyOfRange(audio.size - CONTEXT, audio.size)

        val melValues = run(melModel, "input", audio, longArrayOf(1, audio.size.toLong()))
        val frames = melValues.size / MEL_BINS
        for (f in 0 until frames) {
            val frame = FloatArray(MEL_BINS) { b -> melValues[f * MEL_BINS + b] / 10f + 2f }
            mel.addLast(frame)
        }
        while (mel.size > MEL_WINDOW) mel.removeFirst()

        // 2. Speech embedding of the last 76 mel frames
        val window = FloatArray(MEL_WINDOW * MEL_BINS)
        mel.forEachIndexed { i, frame -> System.arraycopy(frame, 0, window, i * MEL_BINS, MEL_BINS) }
        val embedding = run(embModel, "input_1", window, longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1))
        features.addLast(embedding.copyOf(EMBEDDING))
        while (features.size > FEATURES) features.removeFirst()
        if (features.size < FEATURES) return 0f

        // 3. "Hey Jarvis" score from the last 16 embeddings
        val feats = FloatArray(FEATURES * EMBEDDING)
        features.forEachIndexed { i, e -> System.arraycopy(e, 0, feats, i * EMBEDDING, EMBEDDING) }
        val score = run(wakeModel, "x.1", feats, longArrayOf(1, FEATURES.toLong(), EMBEDDING.toLong()))
        return score.firstOrNull() ?: 0f
    }

    private fun run(session: OrtSession, input: String, data: FloatArray, shape: LongArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
            session.run(mapOf(input to tensor)).use { result ->
                val out = result.get(0) as OnnxTensor
                val buf = out.floatBuffer
                FloatArray(buf.remaining()).also { buf.get(it) }
            }
        }
}
