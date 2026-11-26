package com.erezai.assistant

import android.app.*
import android.app.PendingIntent
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
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_SCREEN_TEXT = "com.erezai.assistant.SCREEN_TEXT"
        const val EXTRA_SCREEN_TEXT = "screen_text"
        const val EXTRA_SCREEN_IMAGE_PATH = "screen_image_path"
        const val EXTRA_SCREEN_FRAME_PATHS = "screen_frame_paths"
        const val ACTION_STOP = "com.erezai.assistant.screen.STOP"
        const val ACTION_SCREEN_STOPPED = "com.erezai.assistant.SCREEN_STOPPED"
        private const val FRAME_INTERVAL_MS = 4000L
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val processing = AtomicBoolean(false)
    private val recentFrames = ArrayDeque<File>()
    // Tesseract is not thread-safe. Initialization and every OCR call share this worker.
    private val ocrWorker = Executors.newSingleThreadExecutor()
    private val hebrewOcr by lazy { HebrewOcr(applicationContext) }
    @Volatile private var hebrewOcrReady = false
    @Volatile private var lastText = ""
    @Volatile private var lastHebrewText = ""
    @Volatile private var lastFrameAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val channel = "erez_screen_capture"
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(channel, "שיתוף מסך פעיל", NotificationManager.IMPORTANCE_LOW))
        }
        val stopIntent = PendingIntent.getService(
            this, 71, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        startForeground(71, NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("ארז AI פעיל")
            .setContentText("ניתוח מסך ותמונות פעיל לבקשתך")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "עצור שיתוף מסך", stopIntent)
            .build())

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
            // Capture at a bounded resolution instead of mirroring every physical pixel.
            // This substantially reduces ImageReader buffers and OCR bitmap allocations.
            val captureWidth = minOf(metrics.widthPixels, 720)
            val captureHeight = (metrics.heightPixels.toFloat() * captureWidth / metrics.widthPixels)
                .toInt().coerceAtLeast(1)
            reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
            reader?.setOnImageAvailableListener({ source -> processLatestFrame(source) }, null)
            display = projection?.createVirtualDisplay(
                "ErezAI-screen", captureWidth, captureHeight, metrics.densityDpi,
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
            // OCR and image inference do not need the phone's full native resolution.
            // Downscale immediately to cap transient bitmap memory and processing cost.
            val maxDimension = 1600
            val scale = minOf(1f, maxDimension.toFloat() / maxOf(cropped.width, cropped.height))
            if (scale < 1f) {
                val scaled = Bitmap.createScaledBitmap(
                    cropped,
                    (cropped.width * scale).toInt().coerceAtLeast(1),
                    (cropped.height * scale).toInt().coerceAtLeast(1),
                    true
                )
                cropped.recycle()
                scaled
            } else cropped
        } catch (e: Exception) {
            Log.w("ErezAI", "Could not convert captured frame", e)
            frame.close()
            processing.set(false)
            return
        }
        frame.close()

        val textTask = try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
        } catch (e: Exception) {
            Log.w("ErezAI", "Could not start OCR", e)
            bitmap.recycle()
            processing.set(false)
            return
        }
        textTask.addOnSuccessListener { result ->
            lastText = result.text.trim()
        }.addOnFailureListener {
            lastText = ""
            Log.w("ErezAI", "OCR frame failed", it)
        }

        textTask.addOnCompleteListener {
            ocrWorker.execute {
                try {
                    if (!hebrewOcrReady) hebrewOcrReady = hebrewOcr.initialize()
                    lastHebrewText = if (hebrewOcrReady) hebrewOcr.recognize(bitmap) else ""
                    val combined = buildString {
                        if (lastText.isNotBlank()) append("טקסט שזוהה במסך:\n").append(lastText)
                        if (lastHebrewText.isNotBlank()) {
                            if (isNotEmpty()) append("\n\n")
                            append("טקסט בעברית שזוהה במסך:\n").append(lastHebrewText)
                        }
                    }.take(5000)

                    val frameDir = File(cacheDir, "erez-recent-frames").apply { mkdirs() }
                    val screenFile = File(frameDir, "frame_" + System.currentTimeMillis() + ".jpg")
                    val imageSaved = try {
                        screenFile.outputStream().buffered().use { output ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, output)
                        }
                    } catch (e: Exception) {
                        Log.w("ErezAI", "Could not persist latest screen frame", e)
                        false
                    }
                    val framePaths = ArrayList<String>()
                    if (imageSaved) {
                        synchronized(recentFrames) {
                            recentFrames.addLast(screenFile)
                            while (recentFrames.size > 2) recentFrames.removeFirst().delete()
                            framePaths.add(screenFile.absolutePath)
                        }
                    }
                    if (combined.isNotBlank() || imageSaved) {
                        sendBroadcast(Intent(ACTION_SCREEN_TEXT).setPackage(packageName)
                            .putExtra(EXTRA_SCREEN_TEXT, combined)
                            .putExtra(EXTRA_SCREEN_IMAGE_PATH, if (imageSaved) screenFile.absolutePath else "")
                            .putStringArrayListExtra(EXTRA_SCREEN_FRAME_PATHS, framePaths))
                    }
                } catch (e: Exception) {
                    Log.w("ErezAI", "Screen OCR failed", e)
                } finally {
                    bitmap.recycle()
                    processing.set(false)
                }
            }
        }
    }

    override fun onDestroy() {
        sendBroadcast(Intent(ACTION_SCREEN_STOPPED).setPackage(packageName))
        display?.release()
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        projection?.stop()
        recognizer.close()
        try {
            ocrWorker.execute { try { hebrewOcr.close() } catch (_: Exception) {} }
            ocrWorker.shutdown()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
