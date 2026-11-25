package com.erezai.assistant

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig

/**
 * On-device text model runtime. The model is downloaded only after the user
 * explicitly starts setup; it is never bundled into the APK.
 */
object ErezAiBrain {
    private const val MODEL_NAME = "Qwen3-0.6B.litertlm"
    private const val MODEL_URL = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm"
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var ready = false
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    @Volatile private var busy = false

    fun isReady(): Boolean = ready

    fun prepare(context: Context, onStatus: (String) -> Unit) {
        executor.execute {
            try {
                if (ready) {
                    onStatus("מנוע הבינה כבר מוכן.")
                    return@execute
                }
                val model = File(context.filesDir, MODEL_NAME)
                if (!model.exists() || model.length() < 100_000_000L) {
                    onStatus("מוריד מודל AI למכשיר. זה קובץ גדול; השאר את האפליקציה פתוחה והשתמש ב-Wi-Fi.")
                    downloadModel(model) { downloaded, total ->
                        val percent = if (total > 0) (downloaded * 100 / total).toInt() else -1
                        onStatus(if (percent >= 0) "מוריד מודל AI: $percent%" else "מוריד מודל AI: ${downloaded / 1_000_000} MB")
                    }
                }
                onStatus("המודל ירד. טוען אותו בזיכרון המכשיר…")
                val created = Engine(
                    EngineConfig(
                        modelPath = model.absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = context.cacheDir.absolutePath
                    )
                )
                try {
                    created.initialize()
                } catch (e: Exception) {
                    // Do not close an Engine whose initialize() failed.
                    throw e
                }
                engine = created
                conversation = created.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(
                            "אתה ארז AI, עוזר אישי בעברית. ענה בעברית טבעית, קצר וברור. " +
                            "אל תטען שביצעת פעולה שלא ביצעת. אם חסר מידע שאל שאלה אחת. " +
                            "לפני שליחת הודעות, פרסום, שיחות או שינויי יומן דרוש אישור מפורש."
                        )
                    )
                )
                ready = true
                onStatus("מנוע הבינה המקומי מוכן. השיחה מתבצעת על המכשיר.")
            } catch (e: Exception) {
                ready = false
                onStatus("טעינת מנוע הבינה נכשלה: ${e.message ?: "שגיאה לא ידועה"}")
            }
        }
    }

    fun ask(prompt: String, onChunk: (String) -> Unit, onDone: () -> Unit, onError: (String) -> Unit) {
        executor.execute {
            if (!ready || conversation == null) {
                onError("מנוע הבינה עדיין לא נטען. הפעל קודם את הורדת המודל.")
                return@execute
            }
            if (busy) {
                onError("אני עדיין מסיים את התשובה הקודמת.")
                return@execute
            }
            busy = true
            try {
                runBlocking {
                    conversation!!.sendMessageAsync(prompt).collect { part ->
                        onChunk(part.toString())
                    }
                }
                onDone()
            } catch (e: Exception) {
                onError(e.message ?: "שגיאה בזמן יצירת תשובה")
            } finally {
                busy = false
            }
        }
    }

    private fun downloadModel(target: File, progress: (Long, Long) -> Unit) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".download")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ErezAI-Android")
        }
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("השרת החזיר HTTP $code")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var count: Int
                    var done = 0L
                    while (input.read(buffer).also { count = it } != -1) {
                        output.write(buffer, 0, count)
                        done += count
                        progress(done, total)
                    }
                    output.flush()
                    if (total > 0 && done != total) throw IllegalStateException("ההורדה לא הושלמה")
                    if (done < 100_000_000L) throw IllegalStateException("קובץ המודל קטן מהצפוי")
                }
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) throw IllegalStateException("לא ניתן לשמור את קובץ המודל")
        } finally {
            connection.disconnect()
            if (temp.exists()) temp.delete()
        }
    }
}
