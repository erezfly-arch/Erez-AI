package com.erezai.assistant

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.Gravity
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.text.util.Linkify
import android.text.method.LinkMovementMethod
import android.widget.Button
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class OverlayAssistantService : Service(), TextToSpeech.OnInitListener {
    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var voiceListening = false
    private var panel: LinearLayout? = null
    private var latestText = ""
    private var latestAudioText = ""
    private var latestVoiceStatus = ""
    private var voiceButton: TextView? = null
    private var latestScreenImagePath = ""
    private var latestScreenFramePaths: List<String> = emptyList()
    private var statusText: TextView? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var pendingSpeech = ""
    private val textReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == VoiceAssistantService.ACTION_WAKE_HEARD) {
                speak("I’m listening. What would you like me to do?")
            } else if (intent?.action == VoiceAssistantService.ACTION_RECOGNIZED) {
                intent.getStringExtra(VoiceAssistantService.EXTRA_COMMAND)?.let { runCommand(it) }
            } else if (intent?.action == VoiceAssistantService.ACTION_TRANSCRIPT) {
                val transcript = intent.getStringExtra(VoiceAssistantService.EXTRA_TRANSCRIPT).orEmpty()
                if (transcript.isNotBlank()) {
                    latestAudioText = (latestAudioText + "\n" + transcript).takeLast(2500)
                    statusText?.text = "זיהוי קולי: $transcript"
                }
            } else if (intent?.action == VoiceAssistantService.ACTION_STATUS) {
                latestVoiceStatus = intent.getStringExtra(VoiceAssistantService.EXTRA_STATUS).orEmpty()
                voiceListening = latestVoiceStatus.contains("מקשיב") || latestVoiceStatus.contains("מוריד מודל") || latestVoiceStatus.contains("מכין זיהוי")
                statusText?.text = latestVoiceStatus
                voiceButton?.text = if (voiceListening) "⏹ עצור האזנה" else "🎙️ דבר עם ארז"
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
            addAction(VoiceAssistantService.ACTION_WAKE_HEARD)
            addAction(VoiceAssistantService.ACTION_TRANSCRIPT)
            addAction(VoiceAssistantService.ACTION_STATUS)
            addAction(ScreenCaptureService.ACTION_SCREEN_STOPPED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(textReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(textReceiver, filter)
        showBubble()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val languageResult = tts?.setLanguage(Locale.US) ?: TextToSpeech.LANG_NOT_SUPPORTED
            val englishVoice = tts?.voices?.firstOrNull { voice ->
                voice.locale.language == "en" &&
                    (voice.name.contains("male", ignoreCase = true) || voice.name.contains("masculine", ignoreCase = true))
            }
            if (englishVoice != null) tts?.voice = englishVoice
            tts?.setPitch(1.0f)
            latestVoiceStatus = if (englishVoice == null) {
                "No dedicated English male voice is installed; using the available English voice."
            } else {
                "English voice is ready."
            }
            statusText?.text = latestVoiceStatus
            tts?.setSpeechRate(0.94f)
            ttsReady = languageResult >= TextToSpeech.LANG_AVAILABLE
            val queued = pendingSpeech
            pendingSpeech = ""
            if (queued.isNotBlank() && ttsReady) speak(queued)
            else if (!ttsReady) statusText?.text = "אין במכשיר קול עברי מותקן. יש להתקין נתוני עברית במנוע הדיבור של Android."
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
        if (tts == null || !ttsReady) {
            pendingSpeech = message
            return
        }
        val containsHebrew = message.any { it.code in 0x0590..0x05FF }
        val requestedLocale = if (containsHebrew) Locale("he", "IL") else Locale.US
        val languageResult = tts?.setLanguage(requestedLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (languageResult < TextToSpeech.LANG_AVAILABLE) {
            val fallback = if (containsHebrew) "Hebrew speech data is not installed on this device." else "English speech data is not installed on this device."
            statusText?.text = fallback
            voiceControl(VoiceAssistantService.ACTION_RESUME)
            return
        }
        voiceControl(VoiceAssistantService.ACTION_PAUSE)
        val result = tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "erez-ai-background")
        if (result == TextToSpeech.ERROR) voiceControl(VoiceAssistantService.ACTION_RESUME)
    }

    private fun layoutParams(width: Int, height: Int, gravity: Int = Gravity.TOP or Gravity.END, focusable: Boolean = false): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(width, height, type,
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { this.gravity = gravity; x = 18; y = 220 }
    }

    private fun showBubble() {
        if (bubble != null) return
        bubble = TextView(this).apply {
            text = "✨"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF7C3AED.toInt(), 0xFF2563EB.toInt(), 0xFF06B6D4.toInt())
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), 0xCCFFFFFF.toInt())
            }
            elevation = dp(10).toFloat()
            contentDescription = "פתח את ארז AI"
            setOnClickListener { if (panel == null) showPanel() else hidePanel() }
        }
        windowManager.addView(bubble, layoutParams(dp(58), dp(58)))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun styledButton(label: String, color: Int, onClick: (TextView) -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0x44FFFFFF)
            }
            elevation = dp(3).toFloat()
            setOnClickListener { onClick(this) }
        }
    }

    private fun toggleVoice(button: TextView) {
        if (voiceListening) {
            startService(Intent(this, VoiceAssistantService::class.java).setAction(VoiceAssistantService.ACTION_STOP))
            voiceListening = false
            button.text = "🎙️ דבר עם ארז"
            speak("ההאזנה נעצרה.")
            return
        }
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            speak("צריך לאשר גישה למיקרופון במסך הראשי של האפליקציה.")
            return
        }
        try {
            val intent = Intent(this, VoiceAssistantService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            voiceListening = true
            button.text = "⏹ עצור האזנה"
            speak("אני מקשיב. דבר אליי בעברית.")
        } catch (e: Exception) {
            speak("לא הצלחתי להפעיל את המיקרופון: ${e.message ?: "שגיאה"}")
        }
    }

    private fun showPanel() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                setColor(0xF2162035.toInt())
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), 0x557C8DFF)
            }
            elevation = dp(16).toFloat()
        }
        val heading = TextView(this).apply {
            text = "✨  ארז AI"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(8))
        }
        val subtitle = TextView(this).apply {
            text = "אפשר לדבר איתי או לכתוב לי"
            textSize = 13f
            setTextColor(0xFFB9C7E8.toInt())
            setPadding(0, 0, 0, dp(12))
        }
        val input = EditText(this).apply {
            hint = "כתוב כאן מה תרצה לעשות…"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF9EACC5.toInt())
            textSize = 16f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(0xFF263550.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0x667C8DFF)
            }
        }
        val status = TextView(this).apply {
            text = when {
                latestVoiceStatus.isNotBlank() -> latestVoiceStatus
                latestText.isNotBlank() -> latestText.take(500)
                else -> "מוכן לעזור לך."
            }
            setTextColor(0xFFE6EDFF.toInt())
            textSize = 13f
            autoLinkMask = Linkify.WEB_URLS
            movementMethod = LinkMovementMethod.getInstance()
            setPadding(0, dp(12), 0, dp(10))
        }
        statusText = status

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val mic = styledButton(if (voiceListening) "⏹ עצור האזנה" else "🎙️ דבר עם ארז", 0xFF7C3AED.toInt()) {
            toggleVoice(it as TextView)
        }
        voiceButton = mic
        actions.addView(mic, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        actions.addView(styledButton("שלח  ➤", 0xFF2563EB.toInt()) {
            runCommand(input.text.toString())
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })

        val secondary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        secondary.addView(styledButton("מה במסך?", 0xFF0F766E.toInt()) {
            runCommand("מה במסך?")
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        secondary.addView(styledButton("סגור", 0xFF475569.toInt()) {
            hidePanel()
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(5) })

        box.addView(heading)
        box.addView(subtitle)
        box.addView(input)
        box.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(12) })
        box.addView(secondary, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(8) })
        box.addView(status)
        panel = box
        windowManager.addView(box, layoutParams((resources.displayMetrics.widthPixels * 0.90f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP, true))
    }

    private fun hidePanel() {
        panel?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        panel = null
        statusText = null
        voiceButton = null
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
                    askAi("תאר בעברית מה מופיע כרגע במסך ומה חשוב לדעת. התבסס על צילום המסך האחרון ועל הטקסט שזוהה. ענה ישירות ובקצרה.\n" + latestText.take(5000) + audioContext, snapshot)
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
                val messageStart = if (command.contains("וואטסאפ")) command.substringAfter("וואטסאפ") else {
                    val marker = command.indexOf("WhatsApp", ignoreCase = true)
                    if (marker >= 0) command.substring(marker + "WhatsApp".length) else command
                }
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
        val onChunk: (String) -> Unit = { chunk ->
            answer.append(chunk)
            val partial = answer.toString()
            Handler(Looper.getMainLooper()).post { statusText?.text = partial }
        }
        val onDone: () -> Unit = {
            Handler(Looper.getMainLooper()).post {
                val reply = answer.toString().trim()
                speak(reply.ifBlank { "המודל סיים בלי להחזיר תשובה. אפשר לנסות שוב." })
            }
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