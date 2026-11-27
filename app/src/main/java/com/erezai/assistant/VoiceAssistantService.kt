package com.erezai.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.ArrayList
import java.util.ArrayDeque
import kotlin.concurrent.thread
import kotlin.math.sqrt
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Visible foreground microphone session with compact local keyword spotting.
 * Whisper is loaded only after the enrolled speaker says the wake phrase.
 */
class VoiceAssistantService : Service() {
    companion object {
        const val ACTION_STOP = "com.erezai.assistant.voice.STOP"
        const val ACTION_RECOGNIZED = "com.erezai.assistant.voice.RECOGNIZED"
        const val ACTION_TRANSCRIPT = "com.erezai.assistant.voice.TRANSCRIPT"
        const val ACTION_STATUS = "com.erezai.assistant.voice.STATUS"
        const val EXTRA_STATUS = "status"
        const val ACTION_WAKE_HEARD = "com.erezai.assistant.voice.WAKE_HEARD"
        const val EXTRA_TRANSCRIPT = "transcript"
        const val ACTION_PAUSE = "com.erezai.assistant.voice.PAUSE"
        const val ACTION_RESUME = "com.erezai.assistant.voice.RESUME"
        const val EXTRA_COMMAND = "command"
        private const val CHANNEL_ID = "erez_voice_assistant"
        private const val NOTIFICATION_ID = 72
        private const val SAMPLE_RATE = 16000
    }

    @Volatile private var running = false
    @Volatile private var paused = false
    private var captureThread: Thread? = null
    private var recorder: AudioRecord? = null
    private var offlineSpeech: OfflineEnglishSpeech? = null

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PAUSE -> paused = true
                ACTION_RESUME -> paused = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(ACTION_PAUSE)
            addAction(ACTION_RESUME)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(controlReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, "Listening stopped."))
            offlineSpeech?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Preparing offline speech recognition…"))
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Microphone permission is missing. Listening stopped.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (captureThread?.isAlive != true) {
            running = true
            captureThread = thread(name = "ErezAI-Offline-Voice", start = true) { runOfflineListening() }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Erez AI voice listening", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun stopPendingIntent(): PendingIntent {
        return PendingIntent.getService(
            this, 72, Intent(this, VoiceAssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
    }

    private fun buildNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Erez AI · Wake-word standby")
            .setContentText(status)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop listening", stopPendingIntent())
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, status))
    }

    private fun runOfflineListening() {
        val identity = VoiceIdentity(applicationContext)
        var kws: KeywordSpotter? = null
        var kwsStream: OnlineStream? = null
        var commandAsr: OfflineEnglishSpeech? = null
        offlineSpeech = null
        var commandMode = false
        var commandDeadline = 0L
        var wakeDetected = false
        try {
            identity.initialize { message -> updateNotification(message) }
            if (!identity.isEnrolled) throw IllegalStateException("No owner voice profile is enrolled. Open Erez AI and set up your voice profile.")

            val kwsConfig = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "erez-kws/encoder.onnx",
                        decoder = "erez-kws/decoder.onnx",
                        joiner = "erez-kws/joiner.onnx"
                    ),
                    tokens = "erez-kws/tokens.txt",
                    numThreads = 1,
                    debug = false,
                    provider = "cpu"
                ),
                maxActivePaths = 4,
                keywordsFile = "erez-kws/hey_erez_keywords.txt",
                keywordsScore = 1.0f,
                keywordsThreshold = 0.25f,
                numTrailingBlanks = 1
            )
            val activeKws = KeywordSpotter(assetManager = assets, config = kwsConfig)
            kws = activeKws
            val activeKwsStream = activeKws.createStream()
            kwsStream = activeKwsStream
            updateNotification("Hey Erez standby is active · compact local keyword detector")

            val minBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBytes <= 0) throw IllegalStateException("The device did not provide a microphone buffer")
            val audio = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBytes * 2, SAMPLE_RATE * 2 * 8)
            )
            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                audio.release()
                throw IllegalStateException("Could not initialize the microphone")
            }
            recorder = audio
            audio.startRecording()

            val shortBuffer = ShortArray(1600)
            val utterance = ArrayList<Float>(SAMPLE_RATE * 10)
            val preRoll = ArrayDeque<Float>(SAMPLE_RATE / 3)
            var speechStarted = false
            var silenceSamples = 0

            while (running) {
                val count = audio.read(shortBuffer, 0, shortBuffer.size)
                if (count <= 0) continue
                val now = android.os.SystemClock.elapsedRealtime()

                if (commandMode && now >= commandDeadline) {
                    commandMode = false
                    try { commandAsr?.close() } catch (_: Exception) {}
                    commandAsr = null
                    offlineSpeech = null
                    wakeDetected = false
                    activeKws.reset(activeKwsStream)
                    updateNotification("Hey Erez standby is active · say Hey Erez")
                }

                if (paused) {
                    utterance.clear()
                    preRoll.clear()
                    speechStarted = false
                    silenceSamples = 0
                    if (!commandMode) activeKws.reset(activeKwsStream)
                    continue
                }

                val samples = FloatArray(count) { shortBuffer[it] / 32768.0f }
                if (!commandMode) {
                    activeKwsStream.acceptWaveform(samples, SAMPLE_RATE)
                    while (activeKws.isReady(activeKwsStream)) {
                        activeKws.decode(activeKwsStream)
                        val result = activeKws.getResult(activeKwsStream)
                        if (result.keyword.isNotBlank()) {
                            wakeDetected = true
                            activeKws.reset(activeKwsStream)
                            break
                        }
                    }
                }

                var energy = 0.0
                for (sample in samples) energy += sample * sample
                val rms = sqrt(energy / count).toFloat()
                if (rms >= 0.0038f) {
                    if (!speechStarted) {
                        utterance.addAll(preRoll)
                        preRoll.clear()
                    }
                    speechStarted = true
                    silenceSamples = 0
                } else if (speechStarted) {
                    silenceSamples += count
                } else {
                    for (sample in samples) preRoll.addLast(sample)
                    while (preRoll.size > SAMPLE_RATE / 3) preRoll.removeFirst()
                }
                if (speechStarted) {
                    for (sample in samples) utterance.add(sample)
                }

                val reachedSilence = silenceSamples >= SAMPLE_RATE * 9 / 10
                val reachedMaxUtterance = utterance.size >= SAMPLE_RATE * 14
                if (speechStarted && (reachedSilence || reachedMaxUtterance)) {
                    if (utterance.size >= SAMPLE_RATE / 2 && (wakeDetected || commandMode)) {
                        val audioSample = utterance.toFloatArray()
                        if (wakeDetected && !commandMode) {
                            wakeDetected = false
                            val ownerMatched = identity.verify(audioSample)
                            if (ownerMatched) {
                                updateNotification("Voice verified. Preparing command recognition…")
                                val asr = OfflineEnglishSpeech(applicationContext)
                                try {
                                    asr.initialize { message -> updateNotification(message) }
                                    commandAsr = asr
                                    offlineSpeech = asr
                                    val transcript = asr.transcribe(audioSample)
                                    val normalized = transcript.trim()
                                    val lower = normalized.lowercase()
                                        .replace(Regex("[^\\p{L}\\p{N}\\s]+"), " ")
                                        .replace(Regex("\\s+"), " ")
                                        .trim()
                                    val command = if (lower == "hey erez" || lower.startsWith("hey erez ")) {
                                        lower.removePrefix("hey erez").trim()
                                    } else ""
                                    if (transcript.isNotBlank()) {
                                        sendBroadcast(
                                            Intent(ACTION_TRANSCRIPT).setPackage(packageName)
                                                .putExtra(EXTRA_TRANSCRIPT, transcript)
                                        )
                                    }
                                    if (command.isNotBlank()) {
                                        sendBroadcast(Intent(ACTION_RECOGNIZED).setPackage(packageName).putExtra(EXTRA_COMMAND, command))
                                        commandAsr?.close()
                                        commandAsr = null
                                        offlineSpeech = null
                                        commandMode = false
                                        activeKws.reset(activeKwsStream)
                                        updateNotification("Hey Erez standby is active · command received")
                                    } else {
                                        commandMode = true
                                        commandDeadline = android.os.SystemClock.elapsedRealtime() + 15_000L
                                        sendBroadcast(Intent(ACTION_WAKE_HEARD).setPackage(packageName))
                                        updateNotification("I'm listening for your command…")
                                    }
                                } catch (e: Exception) {
                                    try { asr.close() } catch (_: Exception) {}
                                    commandAsr = null
                                    offlineSpeech = null
                                    commandMode = false
                                    activeKws.reset(activeKwsStream)
                                    updateNotification("Command recognition failed: " + (e.message ?: "unknown error"))
                                }
                            } else {
                                updateNotification("Hey Erez standby active · voice ignored because the speaker did not match.")
                                activeKws.reset(activeKwsStream)
                            }
                        } else if (commandMode) {
                            val asr = commandAsr
                            if (asr != null) {
                                val transcript = asr.transcribe(audioSample)
                                if (transcript.isNotBlank()) {
                                    val ownerMatched = identity.verify(audioSample)
                                    if (ownerMatched) {
                                        sendBroadcast(
                                            Intent(ACTION_TRANSCRIPT).setPackage(packageName)
                                                .putExtra(EXTRA_TRANSCRIPT, transcript)
                                        )
                                        sendBroadcast(
                                            Intent(ACTION_RECOGNIZED).setPackage(packageName)
                                                .putExtra(EXTRA_COMMAND, transcript.trim())
                                        )
                                    } else {
                                        updateNotification("Hey Erez standby active · voice ignored because the speaker did not match.")
                                    }
                                    commandMode = false
                                    commandDeadline = 0L
                                    try { asr.close() } catch (_: Exception) {}
                                    commandAsr = null
                                    offlineSpeech = null
                                    activeKws.reset(activeKwsStream)
                                    updateNotification("Hey Erez standby is active · say Hey Erez")
                                }
                            }
                        }
                    }
                    utterance.clear()
                    speechStarted = false
                    silenceSamples = 0
                }
            }
        } catch (e: Exception) {
            Log.e("ErezAI", "Offline wake-word session failed", e)
            if (running) updateNotification("Wake-word standby error: " + (e.message ?: "unknown error"))
            running = false
        } finally {
            try { recorder?.stop() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
            try { commandAsr?.close() } catch (_: Exception) {}
            try { kwsStream?.release() } catch (_: Exception) {}
            try { kws?.release() } catch (_: Exception) {}
            try { identity.close() } catch (_: Exception) {}
            offlineSpeech = null
            if (!running) stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, "Listening stopped."))
        offlineSpeech?.cancel()
        try { unregisterReceiver(controlReceiver) } catch (_: Exception) {}
        try { recorder?.stop() } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
