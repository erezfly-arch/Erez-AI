package com.erezai.assistant

import android.content.Context
import android.os.StatFs
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect

/**
 * Fully local multimodal assistant. Text and image inference run on-device;
 * model weights are open-weight Apache-2.0 and are downloaded once by explicit user action.
 */
object ErezAiBrain {
    private const val MODEL_NAME = "Qwen2-VL-2B.litertlm"
    private const val MODEL_URL =
        "https://huggingface.co/litert-community/Qwen2-VL-2B/resolve/be3809f1d80e2adac2ad929bf483c960ca907023/Qwen2-VL-2B.litertlm"
    private const val MODEL_BYTES = 1_783_424_544L
    private const val MODEL_SHA256 = "cf481776bcb16fe539a38c924a67fc213bb5ee55e6fa600729f060e64849066b"
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var ready = false
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    @Volatile private var busy = false

    fun isReady(): Boolean = ready

    fun hasDownloadedModel(context: Context): Boolean {
        val model = File(context.filesDir, MODEL_NAME)
        return model.exists() && model.length() == MODEL_BYTES
    }

    fun prepare(context: Context, onStatus: (String) -> Unit) {
        executor.execute {
            try {
                if (ready) {
                    onStatus("The local AI brain is already ready.")
                    return@execute
                }
                val model = File(context.filesDir, MODEL_NAME)
                val partialModel = File(context.filesDir, MODEL_NAME + ".download")
                val freeBytes = StatFs(context.filesDir.absolutePath).availableBytes
                val remainingBytes = (MODEL_BYTES - partialModel.length()).coerceAtLeast(0L)
                if ((!model.exists() || model.length() != MODEL_BYTES) && freeBytes < remainingBytes + 400_000_000L) {
                    throw IllegalStateException("Free up additional storage to finish installing the local AI brain.")
                }
                if (!model.exists() || model.length() != MODEL_BYTES) {
                    onStatus("Downloading the local AI model with image understanding. Download size is about 1.78 GB. No API usage fees; performance depends on your device.")
                    var lastPercent = -1
                    downloadModel(model) { downloaded, total ->
                        val percent = if (total > 0) (downloaded * 100 / total).toInt() else -1
                        if (percent < 0 || percent != lastPercent) {
                            lastPercent = percent
                            onStatus(if (percent >= 0) "Downloading local AI model: $percent%" else "Downloading AI model: ${downloaded / 1_000_000} MB")
                        }
                    }
                } else {
                    onStatus("The model file already exists. Verifying integrity…")
                    verifySha256(model)
                }
                onStatus("Model verified. Loading the language and vision engine into memory…")
                // Treat engine initialization AND conversation creation as one backend attempt.
                // Some GPU delegates initialize successfully but fail when creating the native
                // conversation. In that case we must close the GPU engine and retry the full
                // sequence on CPU rather than leaving the assistant unusable.
                val runtime = try {
                    onStatus("Starting the model and conversation engine on GPU…")
                    createReadyRuntime(model, Backend.GPU(), Backend.GPU(), context)
                } catch (gpuError: Throwable) {
                    onStatus("GPU initialization failed: " + describeError(gpuError) + ". Trying CPU…")
                    try {
                        createReadyRuntime(model, Backend.CPU(), Backend.CPU(), context)
                    } catch (cpuError: Throwable) {
                        throw IllegalStateException(
                            "Conversation initialization failed on both GPU and CPU. GPU: " +
                                describeError(gpuError) + " | CPU: " + describeError(cpuError),
                            cpuError
                        )
                    }
                }
                engine = runtime.first
                conversation = runtime.second
                ready = true
                onStatus("The local AI brain is ready: conversation, image understanding, and screen analysis. No API usage fees; device resources still apply.")
            } catch (e: Throwable) {
                ready = false
                onStatus("The local AI brain failed to load: " + describeError(e))
            }
        }
    }

    /**
     * When an image is supplied, a fresh conversation is created for that image.
     * The model card recommends one image per conversation to avoid image context bleed.
     */
    fun ask(
        prompt: String,
        onChunk: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
        imageFile: File? = null
    ) {
        executor.execute {
            val activeEngine = engine
            if (!ready || activeEngine == null || conversation == null) {
                onError("The local AI brain is not ready yet. Finish the initial download.")
                return@execute
            }
            if (busy) {
                onError("I am still finishing the previous response.")
                return@execute
            }
            if (imageFile != null && !imageFile.exists()) {
                onError("The screen image file is unavailable.")
                return@execute
            }
            busy = true
            var oneShot: Conversation? = null
            try {
                val target = if (imageFile != null) {
                    activeEngine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(
                                "You are Erez, a capable personal AI assistant. Analyze the supplied screen image, answer the user request directly, and describe only details that are actually visible."
                            ),
                            samplerConfig = SamplerConfig(topK = 20, topP = 0.90, temperature = 0.35),
                            maxOutputToken = 256
                        )
                    ).also { oneShot = it }
                } else conversation!!
                runBlocking {
                    val messages = if (imageFile != null) {
                        Contents.of(Content.ImageFile(imageFile.absolutePath), Content.Text(prompt))
                    } else {
                        Contents.of(prompt)
                    }
                    target.sendMessageAsync(messages).collect { message ->
                        onChunk(message.toString())
                    }
                }
                onDone()
            } catch (e: Exception) {
                onError(e.message ?: "Error while generating a response")
            } finally {
                try { oneShot?.close() } catch (_: Exception) {}
                busy = false
            }
        }
    }

    /**
     * Analyze recent sampled screen frames one by one (fresh conversation per image),
     * then summarize their order and changes with the local language model.
     */
    fun analyzeFrames(
        prompt: String,
        framePaths: List<String>,
        onChunk: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            val activeEngine = engine
            val textConversation = conversation
            if (!ready || activeEngine == null || textConversation == null) {
                onError("The local AI brain is not ready yet.")
                return@execute
            }
            if (busy) {
                onError("I am still finishing the previous response.")
                return@execute
            }
            val usableFrames = framePaths.takeLast(6).map { File(it) }.filter { it.exists() }
            if (usableFrames.isEmpty()) {
                onError("No screen images are currently available for analysis.")
                return@execute
            }
            busy = true
            try {
                val observations = StringBuilder()
                usableFrames.forEachIndexed { index, file ->
                    val oneFrame = activeEngine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(
                                "You analyze video frames. Briefly describe only what is visible in this image, including people, objects, text, and actions."
                            )
                        )
                    )
                    try {
                        val caption = StringBuilder()
                        runBlocking {
                            oneFrame.sendMessageAsync(
                                Contents.of(
                                    Content.ImageFile(file.absolutePath),
                                    Content.Text("Describe this frame accurately and concisely.")
                                )
                            ).collect { message ->
                                caption.append(message.toString())
                            }
                        }
                        observations.append("Frame ").append(index + 1).append(": ")
                            .append(caption.toString().ifBlank { "No description returned" }).append("\n")
                    } finally {
                        try { oneFrame.close() } catch (_: Exception) {}
                    }
                }
                val summaryPrompt = buildString {
                    append(prompt)
                    append("\n\nThese are descriptions of up to six consecutive screen frames in chronological order. ")
                    append("Compare them, explain what changed, and infer what is happening in the video. ")
                    append("Do not invent details that are not present in the descriptions.\n")
                    append(observations)
                }
                runBlocking {
                    textConversation.sendMessageAsync(summaryPrompt).collect { message ->
                        onChunk(message.toString())
                    }
                }
                onDone()
            } catch (e: Exception) {
                onError(e.message ?: "Error while analyzing frames")
            } finally {
                busy = false
            }
        }
    }

    private fun createReadyRuntime(
        model: File, backend: Backend, visionBackend: Backend, context: Context
    ): Pair<Engine, Conversation> {
        val candidate = Engine(
            EngineConfig(
                modelPath = model.absolutePath,
                backend = backend,
                visionBackend = visionBackend,
                // Qwen2-VL is a text-and-vision model; no audio tower is configured.
                maxNumTokens = 2048,
                maxNumImages = 1,
                cacheDir = context.cacheDir.absolutePath
            )
        )
        try {
            candidate.initialize()
            val chat = candidate.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        "You are Erez, the user personal AI assistant. Always communicate in natural, fluent English. Be conversational, direct, clear, and human—not robotic. Understand everyday spoken English, slang, and minor transcription errors. Keep answers concise and do not repeat the question. When asked about the phone screen, rely only on the supplied screenshot and visible text. If screen context is missing or insufficient, say so clearly and never guess. Never claim an action was completed unless it actually was. Ask only one question when essential information is missing. Require explicit confirmation before sending messages, publishing, placing calls, or changing calendar events."
                    ),
                    samplerConfig = SamplerConfig(topK = 20, topP = 0.90, temperature = 0.35),
                    maxOutputToken = 256
                )
            )
            return candidate to chat
        } catch (failure: Throwable) {
            try { candidate.close() } catch (_: Throwable) {}
            throw failure
        }
    }

    private fun describeError(error: Throwable): String {
        var root = error
        while (root.cause != null && root.cause !== root) root = root.cause!!
        return root.javaClass.simpleName + ": " + (root.message ?: error.message ?: "No details")
    }
    private fun downloadModel(target: File, progress: (Long, Long) -> Unit) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".download")

        // Keep a valid completed download, otherwise resume only when the CDN
        // explicitly confirms the requested byte range.
        if (temp.length() > MODEL_BYTES) temp.delete()
        if (temp.length() == MODEL_BYTES) {
            if (sha256(temp).equals(MODEL_SHA256, ignoreCase = true)) {
                if (target.exists()) target.delete()
                if (temp.renameTo(target)) return
                throw IllegalStateException("Could not save the model file")
            }
            temp.delete()
        }

        val chunkSize = 8L * 1024L * 1024L
        while (temp.length() < MODEL_BYTES) {
            val offset = temp.length()
            val end = minOf(offset + chunkSize - 1L, MODEL_BYTES - 1L)
            val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30000
                readTimeout = 600000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ErezAI-Android")
                setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0L) setRequestProperty("Range", "bytes=$offset-$end")
            }
            try {
                connection.connect()
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_OK) {
                    // Hugging Face/Xet may ignore a Range header. Never append a
                    // full response to a partial file: restart and stream it once.
                    if (offset > 0L) {
                        temp.delete()
                        throw RetryFullDownloadException()
                    }
                    val declaredLength = connection.getHeaderFieldLong("Content-Length", -1L)
                    if (declaredLength > 0L && declaredLength != MODEL_BYTES) {
                        throw IllegalStateException("The model server returned an unexpected file size ($declaredLength bytes).")
                    }
                    connection.inputStream.use { input ->
                        java.io.FileOutputStream(temp, false).buffered().use { output ->
                            val buffer = ByteArray(256 * 1024)
                            var received = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                if (received > MODEL_BYTES) {
                                    throw IllegalStateException("The server sent more data than the expected model size.")
                                }
                                output.write(buffer, 0, count)
                                progress(received, MODEL_BYTES)
                            }
                            output.flush()
                        }
                    }
                    if (temp.length() != MODEL_BYTES) {
                        throw IllegalStateException("Download interrupted: received ${temp.length()} of $MODEL_BYTES bytes. Please retry.")
                    }
                    break
                }
                if (code != HttpURLConnection.HTTP_PARTIAL) {
                    throw IllegalStateException("The model server returned HTTP error $code. The partial download was preserved.")
                }
                val contentRange = connection.getHeaderField("Content-Range").orEmpty()
                val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(contentRange)
                    ?: throw IllegalStateException("The model server returned an invalid data range: $contentRange")
                val rangeStart = match.groupValues[1].toLong()
                val rangeEnd = match.groupValues[2].toLong()
                val total = match.groupValues[3].toLong()
                if (rangeStart != offset || rangeEnd < rangeStart || rangeEnd > end || total != MODEL_BYTES) {
                    temp.delete()
                    throw IllegalStateException("The model server returned a range or size that does not match the verified version. The partial download was deleted.")
                }
                val expectedChunk = rangeEnd - rangeStart + 1L
                var received = 0L
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(temp, true).buffered().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (received < expectedChunk) {
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), expectedChunk - received).toInt())
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            received += count
                            progress(offset + received, MODEL_BYTES)
                        }
                        output.flush()
                    }
                }
                if (received != expectedChunk) {
                    throw IllegalStateException("Connection interrupted after $received of $expectedChunk bytes in this chunk. You can resume from this point.")
                }
            } catch (_: RetryFullDownloadException) {
                // The next iteration starts from zero without a Range header.
                continue
            } finally {
                connection.disconnect()
            }
        }

        if (temp.length() != MODEL_BYTES) {
            throw IllegalStateException("Model file size mismatch: ${temp.length()} of $MODEL_BYTES bytes")
        }
        val digest = sha256(temp)
        if (!digest.equals(MODEL_SHA256, ignoreCase = true)) {
            temp.delete()
            throw IllegalStateException("SHA-256 verification failed. The file was deleted to prevent loading a corrupt model. Please download again.")
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) throw IllegalStateException("Could not save the model file")
    }

    private class RetryFullDownloadException : Exception()

    private fun verifySha256(file: File) {
        if (!sha256(file).equals(MODEL_SHA256, ignoreCase = true)) {
            file.delete()
            throw IllegalStateException("An existing model file was invalid and has been deleted. Download it again.")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            var count: Int
            while (input.read(buffer).also { count = it } != -1) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
