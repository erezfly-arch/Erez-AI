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
        "https://huggingface.co/litert-community/Qwen2-VL-2B/resolve/main/Qwen2-VL-2B.litertlm"
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
                    onStatus("מוח הבינה המקומי כבר מוכן.")
                    return@execute
                }
                val model = File(context.filesDir, MODEL_NAME)
                val partialModel = File(context.filesDir, MODEL_NAME + ".download")
                val freeBytes = StatFs(context.filesDir.absolutePath).availableBytes
                val remainingBytes = (MODEL_BYTES - partialModel.length()).coerceAtLeast(0L)
                if ((!model.exists() || model.length() != MODEL_BYTES) && freeBytes < remainingBytes + 400_000_000L) {
                    throw IllegalStateException("נדרש לפנות עוד אחסון פנוי כדי להשלים את התקנת מוח הבינה המקומי.")
                }
                if (!model.exists() || model.length() != MODEL_BYTES) {
                    onStatus("מוריד מוח AI מקומי עם הבנת תמונות. גודל ההורדה כ־1.78GB. אין תשלום או מכסת שימוש.")
                    var lastPercent = -1
                    downloadModel(model) { downloaded, total ->
                        val percent = if (total > 0) (downloaded * 100 / total).toInt() else -1
                        if (percent < 0 || percent != lastPercent) {
                            lastPercent = percent
                            onStatus(if (percent >= 0) "מוריד מודל AI מקומי: $percent%" else "מוריד מודל AI: ${downloaded / 1_000_000} MB")
                        }
                    }
                } else {
                    onStatus("קובץ המודל כבר קיים. מאמת תקינות…")
                    verifySha256(model)
                }
                onStatus("המודל אומת. טוען מנוע שפה וראייה לזיכרון…")
                // Prefer GPU vision for speed, but fall back to CPU so supported
                // devices without a compatible GPU delegate can still run fully offline.
                val created = try {
                    Engine(
                        EngineConfig(
                            modelPath = model.absolutePath,
                            backend = Backend.CPU(),
                            visionBackend = Backend.GPU(),
                            cacheDir = context.cacheDir.absolutePath
                        )
                    ).also { it.initialize() }
                } catch (gpuError: Exception) {
                    onStatus("האצת GPU אינה זמינה במכשיר. מנסה להפעיל את מודל הראייה מקומית על CPU…")
                    Engine(
                        EngineConfig(
                            modelPath = model.absolutePath,
                            backend = Backend.CPU(),
                            visionBackend = Backend.CPU(),
                            cacheDir = context.cacheDir.absolutePath
                        )
                    ).also { it.initialize() }
                }
                engine = created
                conversation = created.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(
                            "אתה ארז AI, עוזר אישי בעברית. ענה בעברית טבעית, קצר וברור. " +
                            "אתה יכול לנתח תמונות ומסכים כאשר נשלחת אליך תמונה. " +
                            "אל תטען שביצעת פעולה שלא ביצעת. אם חסר מידע שאל שאלה אחת. " +
                            "לפני שליחת הודעות, פרסום, שיחות או שינויי יומן דרוש אישור מפורש."
                        )
                    )
                )
                ready = true
                onStatus("מוח הבינה המקומי מוכן: שיחה, הבנת תמונות וניתוח מסך ללא מכסת שימוש.")
            } catch (e: Exception) {
                ready = false
                onStatus("טעינת מוח הבינה נכשלה: ${e.message ?: "שגיאה לא ידועה"}")
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
                onError("מוח הבינה המקומי עדיין לא נטען. יש להשלים את ההורדה הראשונית.")
                return@execute
            }
            if (busy) {
                onError("אני עדיין מסיים את התשובה הקודמת.")
                return@execute
            }
            if (imageFile != null && !imageFile.exists()) {
                onError("קובץ התמונה של המסך אינו זמין.")
                return@execute
            }
            busy = true
            var oneShot: Conversation? = null
            try {
                val target = if (imageFile != null) {
                    activeEngine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(
                                "אתה ארז AI. נתח את התמונה שנשלחה וענה בעברית. תאר רק מה שניתן לראות בפועל."
                            )
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
                onError(e.message ?: "שגיאה בזמן יצירת תשובה")
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
                onError("מוח הבינה המקומי עדיין לא נטען.")
                return@execute
            }
            if (busy) {
                onError("אני עדיין מסיים את התשובה הקודמת.")
                return@execute
            }
            val usableFrames = framePaths.takeLast(6).map { File(it) }.filter { it.exists() }
            if (usableFrames.isEmpty()) {
                onError("אין כרגע תמונות מסך זמינות לניתוח.")
                return@execute
            }
            busy = true
            try {
                val observations = StringBuilder()
                usableFrames.forEachIndexed { index, file ->
                    val oneFrame = activeEngine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(
                                "אתה מנתח פריימים של וידאו. תאר בקצרה בעברית רק מה שנראה בתמונה זו, כולל אנשים, חפצים, טקסט ופעולות."
                            )
                        )
                    )
                    try {
                        val caption = StringBuilder()
                        runBlocking {
                            oneFrame.sendMessageAsync(
                                Contents.of(
                                    Content.ImageFile(file.absolutePath),
                                    Content.Text("תאר את הפריים הזה בצורה מדויקת ותמציתית.")
                                )
                            ).collect { message ->
                                caption.append(message.toString())
                            }
                        }
                        observations.append("פריים ").append(index + 1).append(": ")
                            .append(caption.toString().ifBlank { "לא התקבל תיאור" }).append("\n")
                    } finally {
                        try { oneFrame.close() } catch (_: Exception) {}
                    }
                }
                val summaryPrompt = buildString {
                    append(prompt)
                    append("\n\nאלה תיאורים של עד שישה פריימים עוקבים מהמסך, לפי סדר הזמן. ")
                    append("השווה ביניהם, הסבר מה השתנה ומה כנראה מתרחש בסרטון. ")
                    append("אל תמציא פרטים שאינם מופיעים בתיאורים.\n")
                    append(observations)
                }
                runBlocking {
                    textConversation.sendMessageAsync(summaryPrompt).collect { message ->
                        onChunk(message.toString())
                    }
                }
                onDone()
            } catch (e: Exception) {
                onError(e.message ?: "שגיאה בניתוח פריימים")
            } finally {
                busy = false
            }
        }
    }

    private fun downloadModel(target: File, progress: (Long, Long) -> Unit) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".download")

        // A partial file larger than the manifest size cannot be resumed safely.
        if (temp.length() > MODEL_BYTES) temp.delete()
        if (temp.length() == MODEL_BYTES) {
            if (!sha256(temp).equals(MODEL_SHA256, ignoreCase = true)) temp.delete()
            else {
                if (target.exists()) target.delete()
                if (temp.renameTo(target)) return
                throw IllegalStateException("לא ניתן לשמור את קובץ המודל")
            }
        }

        // Download in bounded byte ranges. This prevents a CDN/Xet response from
        // silently appending an entire object to a resumed partial download.
        val chunkSize = 8L * 1024L * 1024L
        while (temp.length() < MODEL_BYTES) {
            val offset = temp.length()
            val end = minOf(offset + chunkSize - 1L, MODEL_BYTES - 1L)
            val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30000
                readTimeout = 120000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ErezAI-Android")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Range", "bytes=$offset-$end")
            }
            try {
                connection.connect()
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_PARTIAL) {
                    throw IllegalStateException("שרת המודל לא כיבד בקשת טווח (HTTP $code). נסה שוב; ההורדה החלקית נשמרה.")
                }
                val contentRange = connection.getHeaderField("Content-Range").orEmpty()
                val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(contentRange)
                    ?: throw IllegalStateException("שרת המודל החזיר טווח נתונים לא תקין: $contentRange")
                val rangeStart = match.groupValues[1].toLong()
                val rangeEnd = match.groupValues[2].toLong()
                val total = match.groupValues[3].toLong()
                if (rangeStart != offset || rangeEnd != end || total != MODEL_BYTES) {
                    throw IllegalStateException("גודל או טווח קובץ המודל השתנו בשרת (גודל מדווח: $total bytes). לא נשמר קובץ לא תואם.")
                }
                val expectedChunk = end - offset + 1L
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
                    throw IllegalStateException("החיבור נותק לאחר $received מתוך $expectedChunk בתים במקטע. אפשר להמשיך מאותה נקודה.")
                }
            } finally {
                connection.disconnect()
            }
        }

        if (temp.length() != MODEL_BYTES) {
            throw IllegalStateException("גודל קובץ המודל אינו תואם: ${temp.length()} מתוך $MODEL_BYTES bytes")
        }
        val digest = sha256(temp)
        if (!digest.equals(MODEL_SHA256, ignoreCase = true)) {
            temp.delete()
            throw IllegalStateException("בדיקת SHA-256 נכשלה; הקובץ נמחק כדי למנוע טעינת מודל פגום. יש לנסות הורדה מחדש.")
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) throw IllegalStateException("לא ניתן לשמור את קובץ המודל")
    }

    private fun verifySha256(file: File) {
        if (!sha256(file).equals(MODEL_SHA256, ignoreCase = true)) {
            file.delete()
            throw IllegalStateException("קובץ מודל קיים אינו תקין ונמחק. יש להוריד מחדש.")
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
