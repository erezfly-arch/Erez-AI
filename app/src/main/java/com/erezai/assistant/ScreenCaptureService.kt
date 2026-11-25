package com.erezai.assistant

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_SCREEN_TEXT = "com.erezai.assistant.SCREEN_TEXT"
        const val EXTRA_SCREEN_TEXT = "screen_text"
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val processing = AtomicBoolean(false)
    @Volatile private var lastText = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = "erez_screen_capture"
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(channel, "שיתוף מסך פעיל", NotificationManager.IMPORTANCE_LOW))
        }
        startForeground(71, NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("ארז AI פעיל")
            .setContentText("שיתוף המסך וניתוח הטקסט פעילים לבקשתך")
            .setOngoing(true).build())

        if (projection != null) return START_NOT_STICKY
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data", Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        if (resultCode == Activity.RESULT_OK && data != null) {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(resultCode, data)
            val metrics = resources.displayMetrics
            reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 3)
            reader?.setOnImageAvailableListener({ source -> processLatestFrame(source) }, null)
            display = projection?.createVirtualDisplay(
                "ErezAI-screen", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, null
            )
        } else stopSelf()
        return START_NOT_STICKY
    }

    private fun processLatestFrame(source: ImageReader) {
        val frame: Image = try { source.acquireLatestImage() ?: return } catch (_: Exception) { return }
        if (!processing.compareAndSet(false, true)) { frame.close(); return }
        val input = try { InputImage.fromMediaImage(frame, 0) } catch (e: Exception) {
            frame.close(); processing.set(false); return
        }
        recognizer.process(input)
            .addOnSuccessListener { result ->
                val text = result.text.trim()
                if (text.isNotEmpty() && text != lastText) {
                    lastText = text
                    sendBroadcast(Intent(ACTION_SCREEN_TEXT).setPackage(packageName).putExtra(EXTRA_SCREEN_TEXT, text))
                }
            }
            .addOnFailureListener { Log.w("ErezAI", "OCR frame failed", it) }
            .addOnCompleteListener { frame.close(); processing.set(false) }
    }

    override fun onDestroy() {
        display?.release()
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        projection?.stop()
        recognizer.close()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}