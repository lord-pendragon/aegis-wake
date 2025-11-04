package com.example.aegiswake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.sqrt

class MainService : Service() {

    // ---- constants (OWW-friendly) ----
    private val SAMPLE_RATE = 16_000
    private val FRAME_MS = 80
    private val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000 // 1280
    private val WINDOW_SAMPLES = SAMPLE_RATE                  // 1s

    private val WARMUP_MS = 1200L
    private val COOLDOWN_MS = 5000L
    private val DETECT_THRESH = 0.88f

    // 0.88 = Doesnt Work
    // 0.87 = Works if super duper close to the mic
    // 0.86 = Works if super close to the mic
    // 0.84 = Works
    // 0.77 = Works but too sensitive
    // 0.70 = Works
    private val SMOOTH_N = 8

    // ---- notif ----
    private val CHANNEL_ID = "aegis_wake"
    private val NOTIF_ID = 1
    private val ERR_ID = 2

    // ---- model ----
    private val MODEL_ASSET = "aegis_oww.tflite"
    private var tflite: Interpreter? = null

    // ---- loop state ----
    @Volatile private var running = false
    private var audioThread: Thread? = null
    private var serviceStartAt = 0L
    private var lastUiTick = 0L
    private var lastTriggerAt = 0L

    // 1s ring buffer for inference
    private val ring = ShortArray(WINDOW_SAMPLES)
    private var ringPos = 0
    private var filled = 0

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification("Starting…"))
        serviceStartAt = SystemClock.elapsedRealtime()

        try {
            val opts = Interpreter.Options().apply { setNumThreads(2) }
            tflite = Interpreter(loadModelFromAssets(MODEL_ASSET), opts)
            updateNotification("Model loaded, preparing mic…")
        } catch (e: Exception) {
            notifyError("Model init error: ${e.message ?: "unknown"}")
            stopSelf()
            return
        }

        running = true
        audioThread = Thread({ audioLoop() }, "AegisWake-AudioLoop").also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        try { audioThread?.join(800) } catch (_: Exception) {}
        try { tflite?.close() } catch (_: Exception) {}
        tflite = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    // ---- audio + inference loop ----
    private fun audioLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

        // Build AudioRecord with fallbacks
        val attemptOrder = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.MIC to "MIC"
        )

        var record: AudioRecord? = null
        var sourceUsed = "?"
        var minBytes = 0

        for ((src, name) in attemptOrder) {
            val min = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            minBytes = if (min > 0) min else SAMPLE_RATE * 2
            val bufBytes = max(minBytes, SAMPLE_RATE * 2 * 4) // ≥ 4s of 16-bit mono

            val candidate = try {
                AudioRecord.Builder()
                    .setAudioSource(src)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufBytes)
                    .build()
            } catch (_: Exception) { null }

            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                record = candidate
                sourceUsed = name
                break
            } else {
                candidate?.release()
            }
        }

        if (record == null) {
            notifyError("Mic init failed (min=$minBytes). Grant mic permission & reopen app.")
            stopSelf()
            return
        }

        updateNotification("Mic ready ($sourceUsed @16k). Starting…")

        val frame = ShortArray(FRAME_SAMPLES)
        val scores = ArrayDeque<Float>() // simple smoothing

        try {
            record.startRecording()
        } catch (e: Exception) {
            notifyError("Mic start failed: ${e.message}")
            record.release()
            stopSelf(); return
        }

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            notifyError("Mic not recording (state=${record.recordingState}). Background capture blocked.")
            record.release()
            stopSelf(); return
        } else {
            updateNotification("Listening ($sourceUsed)…")
        }

        var zerosInARow = 0

        while (running) {
            // read one 80ms frame
            val n = record.read(frame, 0, FRAME_SAMPLES, AudioRecord.READ_BLOCKING)
            if (n <= 0) {
                zerosInARow++
                if (zerosInARow >= 10) tickUi("Mic stalled ($sourceUsed) n=$n")
                continue
            } else zerosInARow = 0

            // write to 1s ring
            val rp = ringPos
            val end = rp + n
            if (end <= WINDOW_SAMPLES) {
                System.arraycopy(frame, 0, ring, rp, n)
            } else {
                val first = WINDOW_SAMPLES - rp
                System.arraycopy(frame, 0, ring, rp, first)
                System.arraycopy(frame, first, ring, 0, n - first)
            }
            ringPos = (rp + n) % WINDOW_SAMPLES
            filled = (filled + n).coerceAtMost(WINDOW_SAMPLES)

            // wait warmup + full window
            val alive = SystemClock.elapsedRealtime() - serviceStartAt
            if (alive < WARMUP_MS || filled < WINDOW_SAMPLES) {
                tickUi("Warming up… ($sourceUsed)")
                continue
            }

            // quick VAD from 1s window
            val oneSec = FloatArray(WINDOW_SAMPLES)
            var idx = ringPos
            var sumSq = 0.0
            for (i in 0 until WINDOW_SAMPLES) {
                val f = ring[idx] / 32768f
                oneSec[i] = f
                sumSq += (f * f)
                idx++; if (idx == WINDOW_SAMPLES) idx = 0
            }
            val rms = sqrt(sumSq / WINDOW_SAMPLES).toFloat()
            if (rms < 0.01f) {
                tickUi("Silence… RMS=${"%.3f".format(rms)} ($sourceUsed)")
                continue
            }

            // infer & smooth
            val score = runOww(oneSec)
            scores.addLast(score)
            if (scores.size > SMOOTH_N) scores.removeFirst()
            val avg = scores.average().toFloat()
            tickUi("Listening… RMS=${"%.3f".format(rms)} score≈${"%.2f".format(avg)} ($sourceUsed)")

            val now = SystemClock.elapsedRealtime()
            if (avg >= DETECT_THRESH && now - lastTriggerAt >= COOLDOWN_MS) {
                lastTriggerAt = now
                updateNotification("Wake word detected ($sourceUsed)")
                triggerChatGptVoice()
            }
        }

        try { record.stop() } catch (_: Exception) {}
        record.release()
    }

    private fun runOww(oneSec: FloatArray): Float {
        val interpreter = tflite ?: return 0f
        val input = Array(1) { Array(16000) { FloatArray(1) } } // [1,16000,1]
        for (i in 0 until 16000) input[0][i][0] = oneSec[i]
        val out = Array(1) { FloatArray(1) }
        interpreter.run(input, out)
        return out[0][0]
    }

    // ---- launch ChatGPT: bring our Activity (has turnScreenOn in manifest), then ChatGPT ----
    private fun triggerChatGptVoice() {
        // Wake the screen briefly
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "AegisWake:voiceWake"
        )
        wl.acquire(3000)

        // Bring our activity up (it has showWhenLocked/turnScreenOn in manifest)
        try {
            MainActivity.startVoiceFlow(this)
        } catch (e: Exception) {
            notifyError("Launch error: ${e.message}")
        }
    }

    // ---- notifications ----
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Aegis Wake Service", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aegis is listening")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(text))
    }

    private fun tickUi(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUiTick >= 250L) {
            lastUiTick = now
            updateNotification(text)
        }
    }

    private fun notifyError(msg: String) {
        NotificationManagerCompat.from(this).notify(
            ERR_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Aegis Wake error")
                .setContentText(msg)
                .build()
        )
    }

    // ---- model loader ----
    private fun loadModelFromAssets(assetName: String): ByteBuffer {
        try {
            assets.openFd(assetName).use { afd ->
                FileChannel.MapMode.READ_ONLY.let { mode ->
                    return FileInputStream(afd.fileDescriptor).channel
                        .map(mode, afd.startOffset, afd.length)
                }
            }
        } catch (_: Exception) {
            // compressed in APK
            assets.open(assetName).use { input ->
                val bos = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r == -1) break
                    bos.write(buf, 0, r)
                }
                val bytes = bos.toByteArray()
                return ByteBuffer.allocateDirect(bytes.size)
                    .order(ByteOrder.nativeOrder())
                    .apply { put(bytes); rewind() }
            }
        }
    }
}
