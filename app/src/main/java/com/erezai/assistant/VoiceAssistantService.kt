package com.erezai.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat

/**
 * User-started, visible microphone session. Android shows the ongoing foreground-service
 * notification while listening. Speech recognition is provided by the device's recognition
 * service and may use network access depending on device settings/provider.
 */
class VoiceAssistantService : Service() {
    companion object {
        const val ACTION_STOP = "com.erezai.assistant.voice.STOP"
        const val ACTION_RECOGNIZED = "com.erezai.assistant.voice.RECOGNIZED"
        const val EXTRA_COMMAND = "command"
        private const val CHANNEL_ID = "erez_voice_assistant"
        private const val NOTIFICATION_ID = 72
    }

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var shuttingDown = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListening()
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        }
        startListening()
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "האזנה קולית של ארז AI", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 72, Intent(this, VoiceAssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ארז AI מאזין")
            .setContentText("המיקרופון פעיל לבקשתך. לחץ כדי לעצור.")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "עצור האזנה", stopIntent)
            .build()
    }

    private fun startListening() {
        if (shuttingDown || listening || recognizer == null) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "he-IL")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            listening = true
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            listening = false
            stopSelf()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() { listening = false }
        override fun onError(error: Int) {
            listening = false
            if (!shuttingDown && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS &&
                error != SpeechRecognizer.ERROR_CLIENT) {
                android.os.Handler(mainLooper).postDelayed({ startListening() }, 900)
            } else if (!shuttingDown) {
                stopSelf()
            }
        }
        override fun onResults(results: Bundle?) {
            listening = false
            val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!words.isNullOrBlank()) {
                sendBroadcast(Intent(ACTION_RECOGNIZED).setPackage(packageName).putExtra(EXTRA_COMMAND, words))
            }
            if (!shuttingDown) android.os.Handler(mainLooper).postDelayed({ startListening() }, 450)
        }
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun stopListening() {
        shuttingDown = true
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        listening = false
    }

    override fun onDestroy() {
        stopListening()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
