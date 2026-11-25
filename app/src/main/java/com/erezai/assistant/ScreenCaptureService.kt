package com.erezai.assistant

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_SCREEN_TEXT = "com.erezai.assistant.SCREEN_TEXT"
        const val EXTRA_SCREEN_TEXT = "screen_text"
        private const val FRAME_INTERVAL_MS = 900L
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }
    private val processing = AtomicBoolean(false)
    @Volatile private var lastText = ""
    @Volatile private var lastLabels = ""
    @Volatile private var lastFrameAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = "erez_screen_capture"
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(channel, "שיתוף מסך פעיל", NotificationManager.IMPORTANCE_LOW))
        }
        startForeground(71, NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("ארז AI פעיל")
            .setContentText("ניתוח מסך ותמונות פעיל לבקשתך")
            .setOngoing(true).build())

        if (projection != null) return START_NOT_STICKY
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data", Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        if (resultCode == Activity.RESULT_OK && data != null) {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(resultCode, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, android.os.Handler(mainLooper))
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
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAt < FRAME_INTERVAL_MS) {
            try { source.acquireLatestImage()?.close() } catch (_: Exception) {}
            return
        }
        val frame: Image = try { source.acquireLatestImage() ?: return } catch (_: Exception) { return }
        if (!processing.compareAndSet(false, true)) { frame.close(); return }
        lastFrameAt = now
        val bitmap: Bitmap = try {
            val plane = frame.planes[0]
            val paddedWidth = frame.width + (plane.rowStride - plane.pixelStride * frame.width) / plane.pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, frame.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, frame.width, frame.height)
            padded.recycle()
            cropped
        } catch (e: Exception) {
            Log.w("ErezAI", "Could not convert captured frame", e)
            frame.close()
            processing.set(false)
            return
        }
        frame.close()
        val input = InputImage.fromBitmap(bitmap, 0)
        val completed = AtomicBoolean(false)
        val textTask = recognizer.process(input)
        val labelsTask = labeler.process(input)
        textTask.addOnSuccessListener { result ->
            val text = result.text.trim()
            if (text.isNotEmpty()) lastText = text
        }.addOnFailureListener { Log.w("ErezAI", "OCR frame failed", it) }
        labelsTask.addOnSuccessListener { labels ->
            val summary = labels
                .filter { it.confidence >= 0.55f }
                .take(8)
                .joinToString(", ") { "${it.text} (${(it.confidence * 100).toInt()}%)" }
            if (summary.isNotBlank()) lastLabels = summary
        }.addOnFailureListener { Log.w("ErezAI", "Image labeling failed", it) }

        Tasks.whenAllComplete(textTask, labelsTask).addOnCompleteListener {
            val combined = buildString {
                if (lastText.isNotBlank()) append("טקסט שזוהה במסך:\n").append(lastText)
                if (lastLabels.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("תוכן חזותי שזוהה בתמונה (תוויות AI):\n").append(lastLabels)
                }
            }.take(7000)
            if (combined.isNotBlank() && combined != lastText) {
                sendBroadcast(Intent(ACTION_SCREEN_TEXT).setPackage(packageName).putExtra(EXTRA_SCREEN_TEXT, combined))
            } else if (lastText.isNotBlank()) {
                sendBroadcast(Intent(ACTION_SCREEN_TEXT).setPackage(packageName).putExtra(EXTRA_SCREEN_TEXT, lastText))
            }
            bitmap.recycle()
            processing.set(false)
        }
    }

    override fun onDestroy() {
        display?.release()
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        projection?.stop()
        recognizer.close()
        labeler.close()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
