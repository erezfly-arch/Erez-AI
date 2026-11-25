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
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Visible foreground microphone session with fully offline Hebrew Whisper ASR.
 * The mic is user-started and can be paused by the assistant while TTS is speaking.
 */
class VoiceAssistantService : Service() {
    companion object {
        const val ACTION_STOP = "com.erezai.assistant.voice.STOP"
        const val ACTION_RECOGNIZED = "com.erezai.assistant.voice.RECOGNIZED"
        const val ACTION_TRANSCRIPT = "com.erezai.assistant.voice.TRANSCRIPT"
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
    private var offlineSpeech: OfflineHebrewSpeech? = null

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
            offlineSpeech?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("מכין זיהוי דיבור מקומי…"))
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("אין הרשאת מיקרופון. ההאזנה נעצרה.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (captureThread?.isAlive != true) {
            running = true
            captureThread = thread(name = "ErezAI-Offline-Voice", start = true) { runOfflineListening() }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "האזנה קולית של ארז AI", NotificationManager.IMPORTANCE_LOW)
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
            .setContentTitle("ארז AI · האזנה מקומית")
            .setContentText(status)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "עצור האזנה", stopPendingIntent())
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun runOfflineListening() {
        val asr = OfflineHebrewSpeech(applicationContext)
        offlineSpeech = asr
        try {
            updateNotification("מוריד מודל עברית מקומי בפעם הראשונה…")
            asr.initialize { message -> updateNotification(message) }
            updateNotification("מקשיב בעברית · העיבוד מתבצע במכשיר בלבד")

            val minBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBytes <= 0) throw IllegalStateException("המכשיר לא מספק buffer למיקרופון")
            val audio = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBytes * 2, SAMPLE_RATE * 2 * 8)
            )
            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                audio.release()
                throw IllegalStateException("לא ניתן לאתחל את המיקרופון")
            }
            recorder = audio
            audio.startRecording()

            val shortBuffer = ShortArray(1600) // 100 ms
            val utterance = ArrayList<Float>(SAMPLE_RATE * 10)
            var speechStarted = false
            var silenceSamples = 0
            while (running) {
                val count = audio.read(shortBuffer, 0, shortBuffer.size)
                if (count <= 0) continue
                if (paused) {
                    utterance.clear()
                    speechStarted = false
                    silenceSamples = 0
                    continue
                }

                var energy = 0.0
                for (i in 0 until count) {
                    val sample = shortBuffer[i] / 32768.0f
                    energy += sample * sample
                }
                val rms = sqrt(energy / count).toFloat()
                if (rms >= 0.012f) {
                    speechStarted = true
                    silenceSamples = 0
                } else if (speechStarted) {
                    silenceSamples += count
                }
                if (speechStarted) {
                    for (i in 0 until count) utterance.add(shortBuffer[i] / 32768.0f)
                }

                val reachedSilence = silenceSamples >= SAMPLE_RATE * 7 / 10
                val reachedMaxUtterance = utterance.size >= SAMPLE_RATE * 14
                if (speechStarted && (reachedSilence || reachedMaxUtterance)) {
                    if (utterance.size >= SAMPLE_RATE / 2) {
                        val transcript = asr.transcribe(utterance.toFloatArray())
                        if (transcript.isNotBlank()) {
                            sendBroadcast(
                                Intent(ACTION_TRANSCRIPT).setPackage(packageName)
                                    .putExtra(EXTRA_TRANSCRIPT, transcript)
                            )
                            val normalized = transcript.trim()
                            val lower = normalized.lowercase()
                            val wakePrefix = when {
                                lower.startsWith("היי ארז") -> "היי ארז"
                                lower.startsWith("ארז איי איי") -> "ארז איי איי"
                                lower.startsWith("ארז") -> "ארז"
                                else -> ""
                            }
                            if (wakePrefix.isNotEmpty()) {
                                val command = normalized.drop(wakePrefix.length).trim().trimStart(',', ':', '-', ' ')
                                if (command.isNotBlank()) {
                                    sendBroadcast(
                                        Intent(ACTION_RECOGNIZED).setPackage(packageName)
                                            .putExtra(EXTRA_COMMAND, command)
                                    )
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
            Log.e("ErezAI", "Offline voice session failed", e)
            if (running) updateNotification("שגיאה בהאזנה מקומית: ${e.message ?: "לא ידועה"}")
            running = false
        } finally {
            try { recorder?.stop() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
            try { asr.close() } catch (_: Exception) {}
            offlineSpeech = null
            if (!running) stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        offlineSpeech?.cancel()
        try { unregisterReceiver(controlReceiver) } catch (_: Exception) {}
        try { recorder?.stop() } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
