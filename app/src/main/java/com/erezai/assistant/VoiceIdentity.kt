package com.erezai.assistant

import android.content.Context
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Local speaker verification used only to gate the Hey Erez wake phrase.
 * This is a convenience filter, not secure biometric authentication.
 */
class VoiceIdentity(private val context: Context) {
    private val modelFile = File(context.filesDir, "erez-speaker-id/campplus-en.onnx")
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private var extractor: SpeakerEmbeddingExtractor? = null
    private var manager: SpeakerEmbeddingManager? = null

    @Synchronized
    fun initialize(onStatus: (String) -> Unit = {}) {
        if (extractor != null && manager != null) return
        modelFile.parentFile?.mkdirs()
        ensureModel(onStatus)
        val active = SpeakerEmbeddingExtractor(
            config = SpeakerEmbeddingExtractorConfig(
                model = modelFile.absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
        )
        val activeManager = SpeakerEmbeddingManager(active.dim())
        val saved = preferences.getString(KEY_EMBEDDINGS, "").orEmpty()
        val vectors = decodeEmbeddings(saved)
        if (vectors.isNotEmpty() && !activeManager.add(OWNER, vectors.toTypedArray())) {
            active.release()
            activeManager.release()
            throw IllegalStateException("Could not load the saved owner voice profile.")
        }
        extractor = active
        manager = activeManager
    }

    val isEnrolled: Boolean
        get() = decodeEmbeddings(preferences.getString(KEY_EMBEDDINGS, "").orEmpty()).size >= 2

    @Synchronized
    fun enroll(samples: List<FloatArray>) {
        val active = extractor ?: throw IllegalStateException("Speaker verification is not initialized.")
        val vectors = samples.map { computeEmbedding(active, it) }
        if (vectors.size < 2) throw IllegalArgumentException("Record a longer voice sample and try again.")
        val activeManager = manager ?: throw IllegalStateException("Speaker manager is not initialized.")
        if (activeManager.contains(OWNER)) activeManager.remove(OWNER)
        if (!activeManager.add(OWNER, vectors.toTypedArray())) {
            throw IllegalStateException("Could not save the owner voice profile.")
        }
        preferences.edit().putString(KEY_EMBEDDINGS, encodeEmbeddings(vectors)).apply()
    }

    @Synchronized
    fun verify(samples: FloatArray): Boolean {
        val active = extractor ?: return false
        val activeManager = manager ?: return false
        if (!activeManager.contains(OWNER) || samples.size < SAMPLE_RATE * 2) return false
        val embedding = computeEmbedding(active, samples)
        return activeManager.verify(OWNER, embedding, SIMILARITY_THRESHOLD)
    }

    private fun computeEmbedding(active: SpeakerEmbeddingExtractor, samples: FloatArray): FloatArray {
        if (samples.size < SAMPLE_RATE * 2) {
            throw IllegalArgumentException("At least two seconds of clear speech are required.")
        }
        val stream: OnlineStream = active.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            active.compute(stream)
        } finally {
            stream.release()
        }
    }

    private fun ensureModel(onStatus: (String) -> Unit) {
        if (modelFile.exists() && modelFile.length() == MODEL_BYTES &&
            sha256(modelFile).equals(MODEL_SHA256, ignoreCase = true)
        ) return
        modelFile.delete()
        val temp = File(modelFile.parentFile, modelFile.name + ".download")
        temp.delete()
        onStatus("Downloading the local speaker-verification model (about 30 MB)…")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000
            readTimeout = 180000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ErezAI-Android")
        }
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Speaker model download failed: HTTP $code")
            connection.inputStream.use { input ->
                temp.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            if (temp.length() != MODEL_BYTES || !sha256(temp).equals(MODEL_SHA256, ignoreCase = true)) {
                temp.delete()
                throw IllegalStateException("Speaker model integrity verification failed. Please retry.")
            }
            if (!temp.renameTo(modelFile)) throw IllegalStateException("Could not save the speaker model.")
        } finally {
            connection.disconnect()
            temp.delete()
        }
        onStatus("Speaker-verification model is ready.")
    }

    private fun encodeEmbeddings(vectors: List<FloatArray>): String =
        vectors.joinToString(";") { vector -> vector.joinToString(",") { it.toString() } }

    private fun decodeEmbeddings(value: String): List<FloatArray> {
        if (value.isBlank()) return emptyList()
        return value.split(";").mapNotNull { row ->
            val vector = row.split(",").mapNotNull { it.toFloatOrNull() }
            if (vector.isEmpty()) null else vector.toFloatArray()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            var count: Int
            while (input.read(buffer).also { count = it } != -1) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun close() {
        try { manager?.release() } catch (_: Exception) {}
        try { extractor?.release() } catch (_: Exception) {}
        manager = null
        extractor = null
    }

    companion object {
        private const val PREFERENCES = "erez_voice_identity"
        private const val KEY_EMBEDDINGS = "owner_embeddings"
        private const val OWNER = "owner"
        private const val SAMPLE_RATE = 16000
        private const val SIMILARITY_THRESHOLD = 0.6f
        private const val MODEL_BYTES = 29_596_979L
        private const val MODEL_SHA256 = "98daf9bcf1c8abf3db374fec4c27a5eda7c58705bb3f180d057b085a72a88bb5"
        private const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"

        fun hasEnrollment(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_EMBEDDINGS, "").orEmpty().isNotBlank()
    }
}
