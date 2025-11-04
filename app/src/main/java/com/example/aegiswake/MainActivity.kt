package com.example.aegiswake

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.max

class MainActivity : ComponentActivity() {

    // --- UI ---
    private lateinit var status: TextView
    private lateinit var btnPerms: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnPlay: Button
    private lateinit var btnStartVoice: Button
    private lateinit var btnWakeStart: Button
    private lateinit var btnWakeStop: Button

    // --- permissions prompts ---
    private val askMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus(); startWakeServiceIfReady() }
    private val askNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus(); startWakeServiceIfReady() }

    // --- audio config ---
    private val sampleRate = 16_000
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val channelMask = AudioFormat.CHANNEL_IN_MONO
    private val bytesPerSample = 2 // PCM16

    // --- record state ---
    @Volatile private var isRecording = false
    private var recordThread: Thread? = null
    private var recorder: AudioRecord? = null
    private lateinit var wavFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        status = TextView(this).apply { textSize = 20f }
        btnPerms = Button(this).apply {
            text = "Grant Permissions"
            setOnClickListener { requestNeededPerms() }
        }
        btnStart = Button(this).apply {
            text = "Start Recording"
            setOnClickListener { startRecordingSafe() }
        }
        btnStop = Button(this).apply {
            text = "Stop"
            isEnabled = false
            setOnClickListener { stopRecordingSafe() }
        }
        btnPlay = Button(this).apply {
            text = "Play Last"
            setOnClickListener { playLast() }
        }
        btnStartVoice = Button(this).apply {
            text = "Start ChatGPT Voice"
            setOnClickListener { startChatGptVoiceFlow() }
        }
        btnWakeStart = Button(this).apply {
            text = "Start Wakeword Service"
            setOnClickListener { startWakeServiceIfReady() }
        }
        btnWakeStop = Button(this).apply {
            text = "Stop Wakeword Service"
            setOnClickListener { stopWakeService() }
        }

        with(root) {
            addView(status)
            addView(btnPerms)
            addView(btnStart)
            addView(btnStop)
            addView(btnPlay)
            addView(btnStartVoice)
            addView(btnWakeStart)
            addView(btnWakeStop)
        }

        setContentView(root)

        refreshStatus()
        startWakeServiceIfReady() // auto-start if perms already granted
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        startWakeServiceIfReady()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecordingSafe()
    }

    // ---------------- Permissions & Status ----------------

    private fun requestNeededPerms() {
        if (!hasMic()) { askMic.launch(Manifest.permission.RECORD_AUDIO); return }
        if (Build.VERSION.SDK_INT >= 33 && !hasNotif()) { askNotif.launch(Manifest.permission.POST_NOTIFICATIONS); return }
        refreshStatus()
        startWakeServiceIfReady()
    }

    private fun refreshStatus() {
        val listeningReady = hasMic() && (Build.VERSION.SDK_INT < 33 || hasNotif())
        status.text = if (listeningReady) "Aegis is listening" else "Aegis is unable to listen"
        btnPerms.isEnabled = !listeningReady
        btnStart.isEnabled = listeningReady && !isRecording
        btnStop.isEnabled = isRecording
    }

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasNotif(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // ---------------- Wakeword Service control ----------------

    private fun listeningReady(): Boolean =
        hasMic() && (Build.VERSION.SDK_INT < 33 || hasNotif())

    private fun startWakeServiceIfReady() {
        if (!listeningReady()) return
        ContextCompat.startForegroundService(
            this, Intent(this, MainService::class.java)
        )
    }

    private fun stopWakeService() {
        stopService(Intent(this, MainService::class.java))
    }

    // ---------------- Manual ChatGPT Voice Flow ----------------

    private fun startChatGptVoiceFlow() {
        if (!isAegisAccessibilityEnabled()) {
            Toast.makeText(this, "Enable Aegis Accessibility Service to auto-start voice.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }

        val launch = resolveChatGptLaunchIntent()
        if (launch == null) {
            val pm = packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(main, 0)
            val near = apps.map { it.activityInfo.packageName to (it.loadLabel(pm)?.toString() ?: it.activityInfo.name) }
                .filter { (pkg, label) -> pkg.contains("openai", true) || label.contains("chatgpt", true) }
                .joinToString("\n") { (p, l) -> "- $l  ($p)" }

            Toast.makeText(this, "ChatGPT app not found.\nCandidates:\n$near", Toast.LENGTH_LONG).show()

            val pkg = "com.openai.chatgpt"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$pkg")))
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
            }
            return
        }

        // Ask our AccessibilityService to arm a single press once the UI is ready
        AegisAccessibilityService.instance?.requestPressButton()

        // Launch ChatGPT
        startActivity(launch)
    }

    private fun resolveChatGptLaunchIntent(): Intent? {
        val pm = packageManager
        pm.getLaunchIntentForPackage("com.openai.chatgpt")?.let { return it }

        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val candidates = apps.sortedBy { it.activityInfo.packageName }.filter { ri ->
            val pkg = ri.activityInfo.packageName.lowercase()
            val label = ri.loadLabel(pm)?.toString()?.lowercase() ?: ""
            pkg.contains("openai") || label.contains("chatgpt")
        }
        if (candidates.isNotEmpty()) {
            val ri = candidates.first()
            return Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
        return null
    }

    private fun isAegisAccessibilityEnabled(): Boolean {
        return try {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        } catch (_: Exception) { false }
    }

    // ---------------- Simple WAV recorder (manual testing) ----------------

    private fun startRecordingSafe() {
        if (isRecording) return
        if (!hasMic()) {
            Toast.makeText(this, "Mic permission required.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startRecording()
            Toast.makeText(this, "Recording…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Record failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
        refreshStatus()
    }

    private fun stopRecordingSafe() {
        if (!isRecording) return
        try {
            stopRecording()
            Toast.makeText(this, "Saved last_capture.wav", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Stop failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
        refreshStatus()
    }

    private fun startRecording() {
        wavFile = File(cacheDir, "last_capture.wav")

        val minBytes = AudioRecord.getMinBufferSize(sampleRate, channelMask, audioFormat)
        val bufBytes = max(minBytes, sampleRate * bytesPerSample)
        recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(audioFormat)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .build()

        val rec = recorder ?: throw IllegalStateException("AudioRecord null")
        if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord init failed")

        val fos = FileOutputStream(wavFile, false)
        writeWavHeader(fos, sampleRate, 1, 16, 0)

        isRecording = true
        rec.startRecording()

        recordThread = Thread({
            fos.use { out ->
                val shortBuf = ShortArray(1024)
                val byteBuf = ByteArray(shortBuf.size * 2)
                var totalPcmBytes = 0L
                while (isRecording) {
                    val n = rec.read(shortBuf, 0, shortBuf.size, AudioRecord.READ_BLOCKING)
                    if (n > 0) {
                        var j = 0
                        for (i in 0 until n) {
                            val v = shortBuf[i].toInt()
                            byteBuf[j++] = (v and 0xFF).toByte()
                            byteBuf[j++] = ((v shr 8) and 0xFF).toByte()
                        }
                        out.write(byteBuf, 0, j)
                        totalPcmBytes += j
                    }
                }
                patchWavSizes(wavFile, totalPcmBytes)
            }
        }, "Aegis-Recorder").also { it.start() }
    }

    private fun stopRecording() {
        isRecording = false
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        try { recordThread?.join(500) } catch (_: Exception) {}
        recordThread = null
    }

    private fun playLast() {
        val f = File(cacheDir, "last_capture.wav")
        if (!f.exists()) {
            Toast.makeText(this, "No recording yet.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener { it.release() }
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Play failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun writeWavHeader(
        out: FileOutputStream, sampleRate: Int, channels: Int, bitsPerSample: Int, dataSize: Int
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalDataLen = 36 + dataSize

        fun w32(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
        )
        fun w16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

        out.write("RIFF".toByteArray())
        out.write(w32(totalDataLen))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(w32(16))
        out.write(w16(1))
        out.write(w16(channels))
        out.write(w32(sampleRate))
        out.write(w32(byteRate))
        out.write(w16(blockAlign))
        out.write(w16(bitsPerSample))
        out.write("data".toByteArray())
        out.write(w32(dataSize))
    }

    private fun patchWavSizes(file: File, pcmBytes: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            val totalDataLen = 36 + pcmBytes
            raf.seek(4);  raf.write(intLE(totalDataLen.toInt()))
            raf.seek(40); raf.write(intLE(pcmBytes.toInt()))
        }
    }

    private fun intLE(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )

    companion object {
        const val EXTRA_START_VOICE = "EXTRA_START_VOICE"
        fun startVoiceFlow(ctx: android.content.Context) {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(EXTRA_START_VOICE, true)
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_START_VOICE, false) == true) {
            // Arm a single press and launch ChatGPT
            AegisAccessibilityService.instance?.requestPressButton()
            startChatGptVoiceFlow()
        }
    }
}
