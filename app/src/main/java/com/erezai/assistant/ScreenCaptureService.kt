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
        private const val FRAME_INTERVAL_MS = 1200L
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }
    private val processing = AtomicBoolean(false)
    private val recentFrames = ArrayDeque<File>()
    // Tesseract is not thread-safe. Initialization and every OCR call share this worker.
    private val ocrWorker = Executors.newSingleThreadExecutor()
    private val hebrewOcr by lazy { HebrewOcr(applicationContext) }
    @Volatile private var hebrewOcrReady = false
    @Volatile private var lastText = ""
    @Volatile private var lastHebrewText = ""
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
        val textTask = recognizer.process(input)
        val labelsTask = labeler.process(input)
        textTask.addOnSuccessListener { result ->
            val text = result.text.trim()
            if (text.isNotEmpty()) lastText = text
        }.addOnFailureListener { Log.w("ErezAI", "Latin OCR frame failed", it) }
        labelsTask.addOnSuccessListener { labels ->
            val summary = labels.filter { it.confidence >= 0.55f }.take(8)
                .joinToString(", ") { "${it.text} (${(it.confidence * 100).toInt()}%)" }
            if (summary.isNotBlank()) lastLabels = summary
        }.addOnFailureListener { Log.w("ErezAI", "Image labeling failed", it) }

        Tasks.whenAllComplete(textTask, labelsTask).addOnCompleteListener {
            // Hebrew OCR is performed off the UI/capture callback thread and is serialized.
            ocrWorker.execute {
                try {
                    if (!hebrewOcrReady) {
                        hebrewOcrReady = hebrewOcr.initialize()
                    }
                    if (hebrewOcrReady) {
                        val hebrew = hebrewOcr.recognize(bitmap)
                        if (hebrew.isNotBlank()) lastHebrewText = hebrew
                    }
                } catch (e: Exception) {
                    Log.w("ErezAI", "Hebrew OCR initialization/recognition failed", e)
                } finally {
                    val combined = buildString {
                        if (lastText.isNotBlank()) append("טקסט באנגלית שזוהה במסך:\n").append(lastText)
                        if (lastHebrewText.isNotBlank()) {
                            if (isNotEmpty()) append("\n\n")
                            append("טקסט בעברית שזוהה במסך:\n").append(lastHebrewText)
                        }
                        if (lastLabels.isNotBlank()) {
                            if (isNotEmpty()) append("\n\n")
                            append("קטגוריות חזותיות כלליות שזוהו (לא תיאור סמנטי מלא):\n").append(lastLabels)
                        }
                    }.take(9000)
                    val frameDir = File(cacheDir, "erez-recent-frames").apply { mkdirs() }
                    val screenFile = File(frameDir, "frame_" + System.currentTimeMillis() + ".jpg")
                    val imageSaved = try {
                        screenFile.outputStream().buffered().use { output ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
                        }
                    } catch (e: Exception) {
                        Log.w("ErezAI", "Could not persist latest screen frame", e)
                        false
                    }
                    val framePaths = ArrayList<String>()
                    if (imageSaved) {
                        synchronized(recentFrames) {
                            recentFrames.addLast(screenFile)
                            while (recentFrames.size > 6) recentFrames.removeFirst().delete()
                            recentFrames.forEach { framePaths.add(it.absolutePath) }
                        }
                    }
                    if (combined.isNotBlank() || imageSaved) {
                        sendBroadcast(Intent(ACTION_SCREEN_TEXT).setPackage(packageName)
                            .putExtra(EXTRA_SCREEN_TEXT, combined)
                            .putExtra(EXTRA_SCREEN_IMAGE_PATH, if (imageSaved) screenFile.absolutePath else "")
                            .putStringArrayListExtra(EXTRA_SCREEN_FRAME_PATHS, framePaths))
                    }
                    bitmap.recycle()
                    processing.set(false)
                }
            }
        }
    }

    override fun onDestroy() {
        display?.release()
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        projection?.stop()
        recognizer.close()
        labeler.close()
        try {
            ocrWorker.execute { try { hebrewOcr.close() } catch (_: Exception) {} }
            ocrWorker.shutdown()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
