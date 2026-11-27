package com.erezai.assistant

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Multilingual offline speech recognition powered by Whisper Base + sherpa-onnx. The wake phrase is checked in English.
 * Model files are downloaded once to app-private storage; recognition never calls a cloud API.
 */
class OfflineEnglishSpeech(private val context: Context) {
    private val modelDir = File(context.filesDir, "whisper-he-base-int8")
    private var recognizer: OfflineRecognizer? = null
    @Volatile private var activeConnection: HttpURLConnection? = null

    fun prepareFiles(onStatus: (String) -> Unit = {}) {
        synchronized(DOWNLOAD_LOCK) {
            modelDir.mkdirs()
            val encoder = File(modelDir, "base-encoder.int8.onnx")
            val decoder = File(modelDir, "base-decoder.int8.onnx")
            val tokens = File(modelDir, "base-tokens.txt")
            ensureFile(encoder, ENCODER_URL, 20_000_000L) { onStatus("Downloading the offline speech model: encoder…") }
            ensureFile(decoder, DECODER_URL, 80_000_000L) { onStatus("Downloading the offline speech model: decoder…") }
            ensureFile(tokens, TOKENS_URL, 100_000L) { onStatus("Downloading speech tokens…") }
        }
    }

    @Synchronized
    fun initialize(onStatus: (String) -> Unit) {
        if (recognizer != null) return
        prepareFiles(onStatus)
        val encoder = File(modelDir, "base-encoder.int8.onnx")
        val decoder = File(modelDir, "base-decoder.int8.onnx")
        val tokens = File(modelDir, "base-tokens.txt")
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    language = "auto",
                    task = "transcribe"
                ),
                tokens = tokens.absolutePath,
                numThreads = 2,
                provider = "cpu",
                modelType = "whisper"
            )
        )
        recognizer = OfflineRecognizer(config = config)
    }

    fun transcribe(samples: FloatArray): String {
        val active = recognizer ?: throw IllegalStateException("Offline English speech model is not initialized")
        if (samples.isEmpty()) return ""
        val stream = active.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate = 16000)
            active.decode(stream)
            active.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun cancel() {
        try { activeConnection?.disconnect() } catch (_: Exception) {}
    }

    fun close() {
        recognizer?.release()
        recognizer = null
        cancel()
    }

    private fun ensureFile(target: File, url: String, minimumBytes: Long, beforeDownload: () -> Unit) {
        if (target.exists() && target.length() >= minimumBytes) return
        beforeDownload()
        val temp = File(target.parentFile, target.name + ".download")
        if (temp.exists()) temp.delete()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000
            readTimeout = 120000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ErezAI-Android")
        }
        activeConnection = connection
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Model download failed: HTTP $code")
            connection.inputStream.use { input ->
                temp.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            if (temp.length() < minimumBytes) throw IllegalStateException("Downloaded speech model is incomplete: ${target.name}")
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) throw IllegalStateException("Cannot save speech model: ${target.name}")
        } finally {
            connection.disconnect()
            activeConnection = null
            if (temp.exists()) temp.delete()
        }
    }

    companion object {
        private val DOWNLOAD_LOCK = Any()
        private const val BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/"
        private const val ENCODER_URL = BASE + "base-encoder.int8.onnx"
        private const val DECODER_URL = BASE + "base-decoder.int8.onnx"
        private const val TOKENS_URL = BASE + "base-tokens.txt"
    }
}
