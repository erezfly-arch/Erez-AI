package com.erezai.assistant

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Hebrew OCR running fully on-device. The trained data is downloaded once
 * from the public tessdata_fast project and kept in app-private storage.
 * All calls must be made from the same worker thread (TessBaseAPI is not thread-safe).
 */
class HebrewOcr(private val context: Context) {
    private var api: TessBaseAPI? = null

    fun initialize(): Boolean {
        if (api != null) return true
        val root = File(context.filesDir, "tesseract")
        val dataDir = File(root, "tessdata")
        val model = File(dataDir, "heb.traineddata")
        if (!model.exists() || model.length() < 100_000L) {
            dataDir.mkdirs()
            val temp = File(dataDir, "heb.traineddata.download")
            val connection = (URL(HEBREW_DATA_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ErezAI-Android")
            }
            try {
                connection.connect()
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("Hebrew OCR model download failed: HTTP $code")
                connection.inputStream.use { input ->
                    temp.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                if (temp.length() < 100_000L) throw IllegalStateException("Hebrew OCR model file is incomplete")
                if (model.exists()) model.delete()
                if (!temp.renameTo(model)) throw IllegalStateException("Cannot save Hebrew OCR model")
            } finally {
                connection.disconnect()
                if (temp.exists()) temp.delete()
            }
        }
        val engine = TessBaseAPI()
        if (!engine.init(root.absolutePath, "heb")) {
            engine.recycle()
            throw IllegalStateException("Tesseract could not initialize Hebrew language data")
        }
        api = engine
        return true
    }

    fun recognize(bitmap: Bitmap): String {
        val engine = api ?: return ""
        engine.setImage(bitmap)
        return engine.getUTF8Text()?.trim().orEmpty()
    }

    fun close() {
        api?.recycle()
        api = null
    }

    companion object {
        private const val HEBREW_DATA_URL =
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/heb.traineddata"
    }
}
