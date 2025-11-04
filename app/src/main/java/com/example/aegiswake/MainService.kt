package com.example.aegiswake

import android.app.*
import android.content.*
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

class MainService : Service() {

    // ---- config ----
    private val CHANNEL_ID = "aegis_wake"
    private val NOTIF_ID = 1
    private val ERR_ID = 2

    private val modelAssetName = "aegis_oww.tflite"
    private val sampleRate = 16_000
    private val frameMs = 80
    private val frameSamples = sampleRate * frameMs / 1000 // 1280
    private val winSamples = sampleRate                    // 1s = 16000
    private val detectionThreshold = 0.50f
    private val cooldownMs = 2500L
    private val startupGraceMs = 3000L                     // ignore first 3s
    private val smoothN = 8                                // frames for avg

    // ---- runtime ----
    private var tflite: Interpreter? = null
    private var audioThread: Thread? = null
    @Volatile private var running = false
    private var lastTriggerTs = 0L
    private var serviceStart = 0L

    // 1s ring buffer
    private val ring = ShortArray(winSamples)
    private var ringPos = 0
    private var filled = 0


    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification("Aegis is listening"))
        serviceStart = SystemClock.elapsedRealtime()

        try {
            tflite = Interpreter(loadModelFromAssets(modelAssetName), Interpreter.Options().apply {
                setNumThreads(2)
            })
            running = true
            audioThread = Thread({ audioLoop() }, "AegisWake-AudioLoop").also { it.start() }
        } catch (e: Exception) {
            notifyError("Init error: ${e.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        try { audioThread?.join(500) } catch (_: Exception) {}
        try { tflite?.close() } catch (_: Exception) {}
        tflite = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    // ---- audio + inference ----
    private fun audioLoop() {
        val minBytes = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = max(minBytes, frameSamples * 2 * 2) // generous

        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            notifyError("AudioRecord init failed"); stopSelf(); return
        }

        val shortBuf = ShortArray(frameSamples)
        val scores = ArrayDeque<Float>() // smoothing window

        try { record.startRecording() } catch (e: Exception) {
            notifyError("Mic start failed: ${e.message}"); stopSelf(); return
        }

        while (running) {
            val n = record.read(shortBuf, 0, frameSamples, AudioRecord.READ_BLOCKING)
            if (n <= 0) continue

            // (1) write frame into 1s ring
            var rp = ringPos
            val end = rp + n
            if (end <= winSamples) {
                System.arraycopy(shortBuf, 0, ring, rp, n)
            } else {
                val first = winSamples - rp
                System.arraycopy(shortBuf, 0, ring, rp, first)
                System.arraycopy(shortBuf, first, ring, 0, n - first)
            }
            ringPos = (rp + n) % winSamples
            filled = (filled + n).coerceAtMost(winSamples)

            // (2) require full 1s + grace period
            val alive = SystemClock.elapsedRealtime() - serviceStart
            if (filled < winSamples || alive < startupGraceMs) continue

            // (3) build float window & quick VAD
            val oneSec = FloatArray(winSamples)
            var idx = ringPos
            var sumSq = 0.0
            for (i in 0 until winSamples) {
                val f = ring[idx] / 32768f
                oneSec[i] = f
                sumSq += f * f
                idx++; if (idx == winSamples) idx = 0
            }
            val rms = Math.sqrt(sumSq / winSamples).toFloat()
            if (rms < 0.01f) continue  // skip silence

            // (4) inference, smoothing, rising edge
            val score = runOww(oneSec)
            scores.addLast(score)
            if (scores.size > smoothN) scores.removeFirst()
            val avg = scores.average().toFloat()
            val prevAvg = if (scores.size >= 2) scores.dropLast(1).average().toFloat() else 0f

            val now = SystemClock.elapsedRealtime()
            if (prevAvg < detectionThreshold && avg >= detectionThreshold && now - lastTriggerTs >= cooldownMs) {
                lastTriggerTs = now
                triggerChatGptVoice()
                updateNotification("Wake word detected")
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

    // ---- launch ChatGPT; AccessibilityService will tap mic ----
    private fun triggerChatGptVoice() {
        val pkg = "com.openai.chatgpt"
        val launch = packageManager.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (launch != null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "AegisWake:voiceWake"
            )
            wl.acquire(3000)
            startActivity(launch)
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
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
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

    // ---- robust model loader ----
    private fun loadModelFromAssets(assetName: String): ByteBuffer {
        // fast path if not compressed
        try {
            assets.openFd(assetName).use { afd ->
                val input = java.io.FileInputStream(afd.fileDescriptor)
                val ch = input.channel
                return ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
            }
        } catch (_: Exception) {
            // fallback if asset got compressed
            assets.open(assetName).use { input ->
                val bos = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r == -1) break
                    bos.write(buf, 0, r)
                }
                val bytes = bos.toByteArray()
                val bb = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                bb.put(bytes); bb.rewind(); return bb
            }
        }
    }
}
