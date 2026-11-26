package com.erezai.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Offline Hebrew neural speech synthesis using the Piper Hebrew SASpeech voice.
 * Sherpa 1.x performs phonemization locally with the bundled espeak-ng data.
 */
class OfflineHebrewTts(private val context: Context) {
    private val modelDir = File(context.filesDir, "erez-hebrew-male-tts")
    private var engine: OfflineTts? = null
    @Volatile private var connection: HttpURLConnection? = null

    fun initialize(onStatus: (String) -> Unit) {
        if (engine != null) return
        modelDir.mkdirs()
        val model = File(modelDir, "he_IL-saspeech-medium.onnx")
        val tokens = File(modelDir, "tokens.txt")
        val espeakData = File(modelDir, "espeak-ng-data")
        if (!model.exists() || model.length() < 60_000_000L ||
            !tokens.exists() || tokens.length() < 100L ||
            !espeakData.exists() || (espeakData.listFiles()?.isEmpty() != false)
        ) {
            onStatus("Downloading the offline Hebrew voice for the first time…")
            downloadAndExtract()
        }
        if (model.length() < 60_000_000L || tokens.length() < 100L ||
            !espeakData.exists() || (espeakData.listFiles()?.isEmpty() != false)
        ) {
            throw IllegalStateException("The Hebrew voice files were not installed completely.")
        }

        onStatus("Starting the offline Hebrew speech engine…")
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.absolutePath,
                    tokens = tokens.absolutePath,
                    dataDir = espeakData.absolutePath
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
        )
        engine = OfflineTts(config = config)
        onStatus("The offline Hebrew voice is ready.")
    }

    @Synchronized
    fun speak(text: String) {
        val active = engine ?: throw IllegalStateException("The offline Hebrew speech engine is not ready.")
        val generated = active.generate(text = text)
        play(generated.samples, generated.sampleRate)
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) return
        val pcm = ShortArray(samples.size) { index ->
            (samples[index].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, minOf(pcm.size * 2, sampleRate * 2)))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            var offset = 0
            while (offset < pcm.size) {
                val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) throw IllegalStateException("Hebrew audio playback failed.")
                offset += written
            }
            track.stop()
        } finally {
            track.release()
        }
    }

    private fun downloadAndExtract() {
        val archive = File(context.cacheDir, "hebrew-tts-model.zip")
        if (archive.exists()) archive.delete()
        val http = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000
            readTimeout = 240000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ErezAI-Android")
        }
        connection = http
        try {
            http.connect()
            val code = http.responseCode
            if (code !in 200..299) throw IllegalStateException("Hebrew voice download failed: HTTP $code")
            http.inputStream.use { input ->
                archive.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            if (archive.length() < 60_000_000L) {
                throw IllegalStateException("The downloaded voice archive is too small or incomplete.")
            }
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val relative = entry.name.substringAfter("work/hebrew/", "")
                    val allowed = relative == "he_IL-saspeech-medium.onnx" ||
                        relative == "tokens.txt" || relative.startsWith("espeak-ng-data/")
                    if (allowed) {
                        val target = File(modelDir, relative)
                        val rootPath = modelDir.canonicalPath + File.separator
                        if (!target.canonicalPath.startsWith(rootPath)) {
                            throw IllegalStateException("Unsafe path in the voice archive.")
                        }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { output -> zip.copyTo(output) }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val model = File(modelDir, "he_IL-saspeech-medium.onnx")
            val tokens = File(modelDir, "tokens.txt")
            val espeakData = File(modelDir, "espeak-ng-data")
            if (model.length() < 60_000_000L || tokens.length() < 100L ||
                !espeakData.exists() || (espeakData.listFiles()?.isEmpty() != false)
            ) {
                throw IllegalStateException("The voice archive did not contain all required files.")
            }
        } finally {
            http.disconnect()
            connection = null
            archive.delete()
        }
    }

    fun cancelDownload() {
        try { connection?.disconnect() } catch (_: Exception) {}
    }

    fun close() {
        try { engine?.release() } catch (_: Exception) {}
        engine = null
        cancelDownload()
    }

    companion object {
        private const val MODEL_URL =
            "https://github.com/erezfly-arch/Erez-AI/releases/download/hebrew-tts-v1/hebrew-tts-model.zip"
    }
}
