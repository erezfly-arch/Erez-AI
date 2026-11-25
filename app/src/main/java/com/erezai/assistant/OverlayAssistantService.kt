package com.erezai.assistant

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class OverlayAssistantService : Service(), TextToSpeech.OnInitListener {
    private lateinit var windowManager: WindowManager
    private var bubble: Button? = null
    private var panel: LinearLayout? = null
    private var latestText = ""
    private var latestAudioText = ""
    private var latestScreenImagePath = ""
    private var latestScreenFramePaths: List<String> = emptyList()
    private var statusText: TextView? = null
    private var tts: TextToSpeech? = null
    private val textReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == VoiceAssistantService.ACTION_RECOGNIZED) {
                intent.getStringExtra(VoiceAssistantService.EXTRA_COMMAND)?.let { runCommand(it) }
            } else if (intent?.action == VoiceAssistantService.ACTION_TRANSCRIPT) {
                val transcript = intent.getStringExtra(VoiceAssistantService.EXTRA_TRANSCRIPT).orEmpty()
                if (transcript.isNotBlank()) latestAudioText = (latestAudioText + "\n" + transcript).takeLast(2500)
            } else if (intent?.action == ScreenCaptureService.ACTION_SCREEN_STOPPED) {
                latestText = ""
                latestScreenImagePath = ""
                latestScreenFramePaths = emptyList()
                statusText?.text = "שיתוף המסך נעצר."
            } else {
                latestText = intent?.getStringExtra(ScreenCaptureService.EXTRA_SCREEN_TEXT)
                    ?: intent?.getStringExtra(ErezAccessibilityService.EXTRA_ACCESSIBILITY_TEXT).orEmpty()
                latestScreenImagePath = intent?.getStringExtra(ScreenCaptureService.EXTRA_SCREEN_IMAGE_PATH).orEmpty()
                latestScreenFramePaths = intent?.getStringArrayListExtra(ScreenCaptureService.EXTRA_SCREEN_FRAME_PATHS)?.toList().orEmpty()
                statusText?.text = latestText.take(500)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val filter = IntentFilter().apply {
            addAction(ScreenCaptureService.ACTION_SCREEN_TEXT)
            addAction(ErezAccessibilityService.ACTION_ACCESSIBILITY_TEXT)
            addAction(VoiceAssistantService.ACTION_RECOGNIZED)
            addAction(VoiceAssistantService.ACTION_TRANSCRIPT)
            addAction(ScreenCaptureService.ACTION_SCREEN_STOPPED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(textReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(textReceiver, filter)
        showBubble()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("he", "IL")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { voiceControl(VoiceAssistantService.ACTION_PAUSE) }
                override fun onDone(utteranceId: String?) { voiceControl(VoiceAssistantService.ACTION_RESUME) }
                override fun onError(utteranceId: String?) { voiceControl(VoiceAssistantService.ACTION_RESUME) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { voiceControl(VoiceAssistantService.ACTION_RESUME) }
            })
        }
    }

    private fun voiceControl(action: String) {
        sendBroadcast(Intent(action).setPackage(packageName))
    }

    private fun speak(message: String) {
        statusText?.text = message
        if (tts != null) {
            voiceControl(VoiceAssistantService.ACTION_PAUSE)
            val result = tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "erez-ai-background")
            if (result == TextToSpeech.ERROR) voiceControl(VoiceAssistantService.ACTION_RESUME)
        }
    }

    private fun layoutParams(width: Int, height: Int, gravity: Int = Gravity.TOP or Gravity.END, focusable: Boolean = false): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(width, height, type,
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { this.gravity = gravity; x = 18; y = 220 }
    }

    private fun showBubble() {
        if (bubble != null) return
        bubble = Button(this).apply {
            text = "ארז AI"
            setOnClickListener { if (panel == null) showPanel() else hidePanel() }
        }
        windowManager.addView(bubble, layoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT))
    }

    private fun showPanel() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            setBackgroundColor(0xEE172337.toInt())
        }
        val heading = TextView(this).apply {
            text = "ארז AI · העוזר הצף"
            textSize = 18f
            setTextColor(-1)
        }
        val input = EditText(this).apply {
            hint = "מה לעשות?"
            setTextColor(-1)
            setHintTextColor(0xFFB7C4D8.toInt())
        }
        val status = TextView(this).apply {
            text = if (latestText.isBlank()) "אפשר לדבר או לכתוב לעוזר" else latestText.take(500)
            setTextColor(-1)
            textSize = 13f
        }
        statusText = status
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "בצע"
            setOnClickListener { runCommand(input.text.toString()) }
        })
        actions.addView(Button(this).apply {
            text = "מה במסך?"
            setOnClickListener { runCommand("מה במסך?") }
        })
        actions.addView(Button(this).apply { text = "סגור"; setOnClickListener { hidePanel() } })
        box.addView(heading)
        box.addView(input)
        box.addView(status)
        box.addView(actions)
        panel = box
        windowManager.addView(box, layoutParams((resources.displayMetrics.widthPixels * 0.88f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP, true))
    }

    private fun hidePanel() {
        panel?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        panel = null
        statusText = null
    }

    private fun runCommand(raw: String) {
        val command = raw.trim()
        if (command.isBlank()) return
        statusText?.text = "שמעתי: $command"
        when {
            command.contains("מה במסך") || command.contains("מה אתה רואה") || command.contains("תאר את המסך") -> {
                if (latestText.isBlank() && latestScreenImagePath.isBlank()) {
                    speak("אין כרגע תמונת מסך זמינה. הפעל שיתוף מסך או שירות נגישות.")
                } else if (ErezAiBrain.isReady()) {
                    val snapshot = latestScreenImagePath.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
                    val audioContext = if (latestAudioText.isBlank()) "" else "\n\nדיבור שנקלט מהמיקרופון בסרטון או בסביבה (תמלול אוטומטי, ייתכנו שגיאות):\n" + latestAudioText
                    askAi("תאר בעברית מה מופיע במסך, מה קורה בתמונה או בסרטון ומה חשוב לדעת. התבסס על הפריימים, הטקסט והדיבור שנקלט.\n" + latestText.take(5000) + audioContext, snapshot, latestScreenFramePaths)
                } else {
                    speak("זיהיתי טקסט במסך, אבל מנוע הבינה עדיין לא נטען: " + latestText.take(800))
                }
            }
            command.contains("תגובה") || command.contains("הגב") -> {
                if (!ErezAiBrain.isReady()) {
                    speak("כדי לנסח תגובה, צריך לטעון קודם את מוח הבינה המקומי.")
                } else {
                    val context = (latestText + "\\n" + latestAudioText).take(7000)
                    val draft = StringBuilder()
                    speak("אני מנסח תגובה לפי התוכן שמופיע במסך…")
                    ErezAiBrain.ask(
                        "נסח תגובה בעברית טבעית וקצרה שמתאימה לפוסט או לסרטון שמופיעים במסך. " +
                            "אל תמציא עובדות. החזר רק את נוסח התגובה. בקשת המשתמש: " + command +
                            "\\nתוכן המסך והדיבור שנקלט:\\n" + context,
                        { part -> draft.append(part) },
                        {
                            Handler(Looper.getMainLooper()).post {
                                confirmAction(ActionConfirmationActivity.TYPE_COMMENT, "טיוטת תגובה להעתקה", draft.toString())
                            }
                        },
                        { error -> Handler(Looper.getMainLooper()).post { speak(error) } }
                    )
                }
            }
            command.contains("וואטסאפ") || command.contains("WhatsApp", true) -> {
                val messageStart = if (command.contains("וואטסאפ")) command.substringAfter("וואטסאפ") else command.substringAfter("WhatsApp", command, ignoreCase = true)
                val body = messageStart.trim().replaceFirst(Regex("^ל[^\\s,:]+:?\\s*"), "")
                val phone = Regex("\\+?[0-9][0-9\\- ]{6,}").find(command)?.value.orEmpty()
                confirmAction(ActionConfirmationActivity.TYPE_WHATSAPP, "שליחת הודעת וואטסאפ", body, phone = phone)
            }
            command.contains("התקשר") || command.contains("חייג") -> {
                val number = Regex("\\+?[0-9][0-9\\- ]{6,}").find(command)?.value.orEmpty()
                confirmAction(
                    ActionConfirmationActivity.TYPE_CALL,
                    if (number.isBlank()) "בחר איש קשר לחיוג" else "חיוג למספר $number",
                    "",
                    phone = number
                )
            }
            command.contains("מייל") || command.contains("אימייל") -> {
                val recipient = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").find(command)?.value.orEmpty()
                val subject = command.substringAfter("נושא", "הודעה מארז AI").substringBefore("תוכן").trim().ifBlank { "הודעה מארז AI" }
                if (ErezAiBrain.isReady()) {
                    val draft = StringBuilder()
                    ErezAiBrain.ask(
                        "נסח גוף של הודעת דואר בעברית לפי בקשת המשתמש. החזר רק את גוף ההודעה, בלי כותרות. בקשה: " + command,
                        { part -> draft.append(part) },
                        { Handler(Looper.getMainLooper()).post {
                            confirmAction(ActionConfirmationActivity.TYPE_EMAIL, subject, draft.toString(), recipient = recipient)
                        } },
                        { error -> Handler(Looper.getMainLooper()).post { speak(error) } }
                    )
                } else {
                    val body = command.substringAfter("תוכן", command.substringAfter("כתוב", command)).trim()
                    confirmAction(ActionConfirmationActivity.TYPE_EMAIL, subject, body, recipient = recipient)
                }
            }
            command.contains("תזכורת") || command.contains("יומן") -> {
                val title = command.substringAfter("תזכורת", command.substringAfter("יומן", command)).trim()
                confirmAction(ActionConfirmationActivity.TYPE_CALENDAR, title.ifBlank { "תזכורת חדשה" }, "")
            }
            command.contains("חפש") || command.contains("אינטרנט") -> {
                val query = command.removePrefix("חפש").removePrefix(" לי").removeSuffix("באינטרנט").trim()
                searchWeb(query.ifBlank { command })
            }
            else -> {
                if (ErezAiBrain.isReady()) {
                    val context = if (latestText.isBlank()) "" else "\n\nתוכן גלוי כרגע במסך (ייתכן שהוא חלקי):\n" + latestText.take(5000)
                    askAi(command + context)
                } else speak("מנוע הבינה עדיין לא מוכן. פתח את האפליקציה הראשית ולחץ על הורד והפעל מוח AI מקומי.")
            }
        }
    }

    private fun searchWeb(query: String) {
        speak("מחפש ברשת. החיפוש אינו דורש מפתח API או תשלום.")
        Thread {
            try {
                val results = FreeWebSearch.search(query)
                if (results.isEmpty()) {
                    Handler(Looper.getMainLooper()).post { speak("לא התקבלו תוצאות חיפוש. אפשר לנסות ניסוח אחר או לפתוח את הדפדפן.") }
                    return@Thread
                }
                val sourceText = results.mapIndexed { index, result ->
                    "${index + 1}. ${result.title}\n${result.snippet}\nקישור: ${result.url}"
                }.joinToString("\n\n")
                if (!ErezAiBrain.isReady()) {
                    Handler(Looper.getMainLooper()).post {
                        statusText?.text = sourceText
                        speak("מצאתי ${results.size} תוצאות. טען את מוח הבינה המקומי כדי לקבל סיכום.")
                    }
                    return@Thread
                }
                val answer = StringBuilder()
                ErezAiBrain.ask(
                    "ענה בעברית על השאלה: " + query +
                        "\nהשתמש רק במידע הבא. ציין אי-ודאות, ובסוף הצג את הקישורים שסופקו ללא שינוי.\n\n" + sourceText,
                    { part -> answer.append(part) },
                    {
                        Handler(Looper.getMainLooper()).post {
                            statusText?.text = answer.toString() + "\n\nמקורות:\n" + results.joinToString("\n") { it.title + " — " + it.url }
                            speak(answer.toString().take(900))
                        }
                    },
                    { error -> Handler(Looper.getMainLooper()).post { speak(error) } }
                )
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    speak("החיפוש נכשל: ${e.message ?: "שגיאת רשת"}. אפשר לפתוח את הדפדפן.")
                }
            }
        }.start()
    }

    private fun confirmAction(
        type: String,
        title: String,
        body: String,
        recipient: String = "",
        phone: String = ""
    ) {
        val intent = Intent(this, ActionConfirmationActivity::class.java)
            .putExtra(ActionConfirmationActivity.EXTRA_TYPE, type)
            .putExtra(ActionConfirmationActivity.EXTRA_TITLE, title)
            .putExtra(ActionConfirmationActivity.EXTRA_BODY, body)
            .putExtra(ActionConfirmationActivity.EXTRA_RECIPIENT, recipient)
            .putExtra(ActionConfirmationActivity.EXTRA_PHONE, phone)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun askAi(
        prompt: String,
        imageFile: java.io.File? = null,
        framePaths: List<String> = emptyList()
    ) {
        val answer = StringBuilder()
        speak("אני מנתח את התוכן…")
        val onChunk: (String) -> Unit = { chunk -> answer.append(chunk) }
        val onDone: () -> Unit = {
            Handler(Looper.getMainLooper()).post { speak(answer.toString().ifBlank { "לא התקבלה תשובה." }) }
        }
        val onError: (String) -> Unit = { error ->
            Handler(Looper.getMainLooper()).post { speak(error) }
        }
        if (framePaths.size > 1) {
            ErezAiBrain.analyzeFrames(prompt, framePaths, onChunk, onDone, onError)
        } else {
            ErezAiBrain.ask(prompt, onChunk, onDone, onError, imageFile)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(textReceiver) } catch (_: Exception) {}
        hidePanel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        bubble?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}