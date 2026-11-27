package com.erezai.assistant

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.text.util.Linkify
import android.text.method.LinkMovementMethod
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var log: TextView
    private lateinit var command: EditText
    private lateinit var modelRetryButton: Button
    private var latestScreenText = ""
    private var latestScreenImagePath = ""
    private var latestScreenFramePaths: List<String> = emptyList()
    private var tts: TextToSpeech? = null
    private var pendingVoiceStart = false
    private var voiceStartupRequested = false
    private var screenConsentRequested = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenCaptureService.ACTION_SCREEN_STOPPED) {
                latestScreenText = ""
                latestScreenImagePath = ""
                latestScreenFramePaths = emptyList()
                say("שיתוף המסך נעצר.")
            } else {
                latestScreenText = intent?.getStringExtra(ScreenCaptureService.EXTRA_SCREEN_TEXT)
                    ?: intent?.getStringExtra(ErezAccessibilityService.EXTRA_ACCESSIBILITY_TEXT).orEmpty()
                latestScreenImagePath = intent?.getStringExtra(ScreenCaptureService.EXTRA_SCREEN_IMAGE_PATH).orEmpty()
                latestScreenFramePaths = intent?.getStringArrayListExtra(ScreenCaptureService.EXTRA_SCREEN_FRAME_PATHS)?.toList().orEmpty()
            }
        }
    }

    private val screenConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val i = Intent(this, ScreenCaptureService::class.java)
                .putExtra("resultCode", result.resultCode).putExtra("data", result.data)
            ContextCompat.startForegroundService(this, i)
            say("Screen sharing is on. I can analyze images and text while you use other apps. The notification shows when sharing is active.")
            startVoiceService()
        } else {
            say("Screen sharing was not approved. Voice chat can continue, but I cannot see your screen.")
            startVoiceService()
        }
    }

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) ensureOverlayAndStartVoice()
        else say("כדי שארז יוכל להאזין ולדבר, נדרשת הרשאת מיקרופון. אפשר לאשר אותה בהגדרות Android.")
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) say("התראות לא אושרו; ממשיך בהגדרת ארז AI.")
        requestMicrophonePermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        buildUi()
        // First-run setup is automatic: prepare the local AI model and request
        // the minimum permissions in sequence instead of asking the user to find
        // separate download/start buttons.
        say("Preparing Erez AI. The local model download is large and requires Wi-Fi and free storage.")
        prepareBrain()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestMicrophonePermission()
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingVoiceStart && android.provider.Settings.canDrawOverlays(this)) {
            pendingVoiceStart = false
            requestScreenCaptureConsent()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(ScreenCaptureService.ACTION_SCREEN_TEXT)
            addAction(ErezAccessibilityService.ACTION_ACCESSIBILITY_TEXT)
            addAction(ScreenCaptureService.ACTION_SCREEN_STOPPED)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        super.onStop()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("en", "US")
            val englishVoice = tts?.voices?.firstOrNull { it.locale.language == "en" && it.locale.country.equals("US", true) }
            if (englishVoice != null) tts?.voice = englishVoice
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(0.94f)
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { sendBroadcast(Intent(VoiceAssistantService.ACTION_PAUSE).setPackage(packageName)) }
                override fun onDone(utteranceId: String?) { sendBroadcast(Intent(VoiceAssistantService.ACTION_RESUME).setPackage(packageName)) }
                override fun onError(utteranceId: String?) { sendBroadcast(Intent(VoiceAssistantService.ACTION_RESUME).setPackage(packageName)) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { sendBroadcast(Intent(VoiceAssistantService.ACTION_RESUME).setPackage(packageName)) }
            })
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            setBackgroundColor(0xFF101827.toInt())
        }
        val title = TextView(this).apply {
            text = "Erez AI"; textSize = 30f; setTextColor(-1)
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 18)
        }
        val subtitle = TextView(this).apply {
            text = "Your personal assistant · English + Hebrew"; setTextColor(0xFFB7C4D8.toInt()); gravity = Gravity.CENTER
        }
        command = EditText(this).apply {
            hint = "Tell me what to do…"; textSize = 17f; setTextColor(-1); setHintTextColor(0xFF9AA8BA.toInt())
        }
        log = TextView(this).apply {
            text = "שלום. ארז AI ממתין לטעינת המודל המקומי.\n"
            setTextColor(-1)
            textSize = 16f
            setPadding(0, 20, 0, 20)
            autoLinkMask = Linkify.WEB_URLS
            movementMethod = LinkMovementMethod.getInstance()
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(command)
        root.addView(TextView(this).apply {
            text = "AI model setup runs automatically in the background."
            setTextColor(0xFFB7C4D8.toInt())
            textSize = 14f
            setPadding(0, 12, 0, 12)
        })
        root.addView(button("שלח הוראה") { handle(command.text.toString()) })
        modelRetryButton = button("נסה לטעון את מוח הבינה שוב") { prepareBrain() }.apply { visibility = android.view.View.GONE }
        root.addView(modelRetryButton)
        root.addView(TextView(this).apply {
            text = "Conversation, listening, and screen access start after Android permissions. Stop listening from the Erez AI notification."
            setTextColor(0xFFB7C4D8.toInt())
            textSize = 13f
            setPadding(0, 10, 0, 10)
        })
        root.addView(ScrollView(this).apply { addView(log) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun prepareBrain() {
        if (ErezAiBrain.isReady()) {
            say("מוח הבינה כבר מוכן.")
            return
        }
        if (::modelRetryButton.isInitialized) {
            modelRetryButton.isEnabled = false
            modelRetryButton.text = "טוען את מוח הבינה…"
            modelRetryButton.visibility = android.view.View.VISIBLE
        }
        ErezAiBrain.prepare(this) { message ->
            runOnUiThread {
                say(message)
                val failed = message.startsWith("טעינת מוח הבינה נכשלה")
                val ready = message.contains("מוח הבינה המקומי מוכן")
                if (::modelRetryButton.isInitialized) {
                    modelRetryButton.visibility = if (failed) android.view.View.VISIBLE else android.view.View.GONE
                    modelRetryButton.isEnabled = failed
                    modelRetryButton.text = if (failed) "הטעינה נכשלה — נסה שוב" else if (ready) "מוח הבינה מוכן" else "טוען את מוח הבינה…"
                }
            }
        }
    }

    private fun requestMicrophonePermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            ensureOverlayAndStartVoice()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestVoicePermissionAndStart() {
        requestMicrophonePermission()
    }

    private fun ensureOverlayAndStartVoice() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            pendingVoiceStart = true
            say("Allow Erez AI to display over other apps. Approve the Android permission; setup will continue when you return.")
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        requestScreenCaptureConsent()
    }

    private fun requestScreenCaptureConsent() {
        if (screenConsentRequested) return
        screenConsentRequested = true
        say("Android needs your one-time approval for screen sharing. Approve the dialog so Erez can understand what's displayed in other apps.")
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenConsent.launch(mgr.createScreenCaptureIntent())
    }

    private fun startVoiceService() {
        if (voiceStartupRequested) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            pendingVoiceStart = true
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        voiceStartupRequested = true;
        try {
            ContextCompat.startForegroundService(this, Intent(this, VoiceAssistantService::class.java))
            // Keep overlay startup lightweight; do not load heavy AI models here.
            startService(Intent(this, OverlayAssistantService::class.java))
            say("Voice assistant is starting. Say “Hey Erez” to wake me. I understand English and Hebrew. You can stop listening from the notification.")
        } catch (_: Exception) {
            voiceStartupRequested = false
            say("לא הצלחתי להפעיל האזנה ברקע. בדוק שהרשאת המיקרופון וההתראות מאושרות.")
        }
    }

    private fun searchWeb(query: String) {
        say("מחפש ברשת ללא מפתח API…")
        Thread {
            try {
                val results = FreeWebSearch.search(query)
                if (results.isEmpty()) {
                    runOnUiThread { speak("לא התקבלו תוצאות חיפוש.") }
                    return@Thread
                }
                val sourceText = results.mapIndexed { index, result ->
                    "${index + 1}. ${result.title}\n${result.snippet}\nקישור: ${result.url}"
                }.joinToString("\n\n")
                if (!ErezAiBrain.isReady()) {
                    runOnUiThread {
                        log.append("\\n" + sourceText)
                        speak("מצאתי ${results.size} תוצאות. טען את מוח הבינה המקומי כדי לקבל סיכום.")
                    }
                } else {
                    val answer = StringBuilder()
                    ErezAiBrain.ask(
                        "Answer in clear English: " + query +
                            "\\nהשתמש רק במידע הבא. ציין אי-ודאות והצג את הקישורים שסופקו ללא שינוי.\\n\\n" + sourceText,
                        { part -> answer.append(part) },
                        {
                            runOnUiThread {
                                log.append("\\n" + answer.toString())
                                log.append("\\nמקורות:\\n" + results.joinToString("\\n") { it.title + " — " + it.url })
                                speak(answer.toString().take(900))
                            }
                        },
                        { error -> runOnUiThread { speak(error) } }
                    )
                }
            } catch (e: Exception) {
                runOnUiThread { speak("החיפוש נכשל: ${e.message ?: "שגיאת רשת"}") }
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
        startActivity(
            Intent(this, ActionConfirmationActivity::class.java)
                .putExtra(ActionConfirmationActivity.EXTRA_TYPE, type)
                .putExtra(ActionConfirmationActivity.EXTRA_TITLE, title)
                .putExtra(ActionConfirmationActivity.EXTRA_BODY, body)
                .putExtra(ActionConfirmationActivity.EXTRA_RECIPIENT, recipient)
                .putExtra(ActionConfirmationActivity.EXTRA_PHONE, phone)
        )
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
    private fun say(message: String) { if (::log.isInitialized) log.append("\n$message\n") }
    private fun speak(message: String) {
        say(message)
        if (tts != null) {
            val containsHebrew = message.any { it in '\u0590'..'\u05FF' }
            val desiredLanguage = if (containsHebrew) Locale("he", "IL") else Locale("en", "US")
            val languageResult = tts?.setLanguage(desiredLanguage)
            if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(if (containsHebrew) Locale("en", "US") else Locale("he", "IL"))
            }
            sendBroadcast(Intent(VoiceAssistantService.ACTION_PAUSE).setPackage(packageName))
            val result = tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "erez-ai-utterance")
            if (result == TextToSpeech.ERROR) sendBroadcast(Intent(VoiceAssistantService.ACTION_RESUME).setPackage(packageName))
        }
    }

    private fun handle(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        say("אתה: $t")
        when {
            t.contains("מה אתה רואה") || t.contains("תאר את המסך") || t.contains("what do you see", true) || t.contains("describe the screen", true) || t.contains("show me the screen", true) -> {
                if (!ErezAiBrain.isReady()) {
                    speak("מוח הבינה המקומי עדיין לא נטען. יש להשלים את ההורדה הראשונית.")
                } else {
                    val snapshot = latestScreenImagePath.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
                    val answer = StringBuilder()
                    val prompt = "Describe in clear English what is visible on the screen and what is happening. Understand and transcribe Hebrew text accurately. Use the screenshot itself and the extracted text.\\n" + latestScreenText.take(5000)
                    val onChunk: (String) -> Unit = { part -> answer.append(part) }
                    val onDone: () -> Unit = { runOnUiThread { speak(answer.toString().ifBlank { "הניתוח הסתיים." }) } }
                    val onError: (String) -> Unit = { error -> runOnUiThread { speak(error) } }
                    // Answer from the newest screenshot immediately. Multi-frame video
                    // analysis is an explicit heavier task, not the default screen response.
                    ErezAiBrain.ask(prompt, onChunk, onDone, onError, snapshot)
                }
            }
            t.contains("תגובה") || t.contains("הגב") || t.contains("write a comment", true) || t.contains("reply", true) -> {
                if (!ErezAiBrain.isReady()) speak("The local AI model must be ready before I can draft a comment.")
                else {
                    val draft = StringBuilder()
                    ErezAiBrain.ask(
                        "Write a concise natural English comment appropriate for the post or video on screen. Return only the comment. Understand Hebrew screen content. User request: " +
                            t + "\\nתוכן המסך:\\n" + latestScreenText.take(5000),
                        { part -> draft.append(part) },
                        { runOnUiThread { confirmAction(ActionConfirmationActivity.TYPE_COMMENT, "טיוטת תגובה להעתקה", draft.toString()) } },
                        { error -> runOnUiThread { speak(error) } }
                    )
                }
            }
            t.contains("וואטסאפ") || t.contains("whatsapp", ignoreCase = true) -> {
                val messageStart = if (t.contains("וואטסאפ")) t.substringAfter("וואטסאפ") else {
                    val marker = t.indexOf("whatsapp", ignoreCase = true)
                    if (marker >= 0) t.substring(marker + "whatsapp".length) else t
                }
                val msg = messageStart.trim().replaceFirst(Regex("^ל[^\\s,:]+:?\\s*"), "")
                val phone = Regex("\\+?[0-9][0-9\\- ]{6,}").find(t)?.value.orEmpty()
                confirmAction(ActionConfirmationActivity.TYPE_WHATSAPP, "שליחת הודעת וואטסאפ", msg, phone = phone)
            }
            t.contains("מייל") || t.contains("אימייל") || t.contains("email", true) || t.contains("e-mail", true) || t.contains("email", true) || t.contains("e-mail", true) -> {
                val recipient = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").find(t)?.value.orEmpty()
                val subject = t.substringAfter("נושא", "הודעה מארז AI").substringBefore("תוכן").trim().ifBlank { "הודעה מארז AI" }
                if (ErezAiBrain.isReady()) {
                    val draft = StringBuilder()
                    ErezAiBrain.ask(
                        "Write the email body in concise natural English. Return only the body, without headings. Request: " + t,
                        { part -> draft.append(part) },
                        { runOnUiThread { confirmAction(ActionConfirmationActivity.TYPE_EMAIL, subject, draft.toString(), recipient = recipient) } },
                        { error -> runOnUiThread { speak(error) } }
                    )
                } else {
                    val body = t.substringAfter("תוכן", t.substringAfter("כתוב", t)).trim()
                    confirmAction(ActionConfirmationActivity.TYPE_EMAIL, subject, body, recipient = recipient)
                }
            }
            t.contains("התקשר") || t.contains("חייג") || t.contains("call ", true) || t.startsWith("call", true) || t.contains("dial ", true) || t.contains("call ", true) || t.startsWith("call", true) || t.contains("dial ", true) -> {
                val number = Regex("\\+?[0-9][0-9\\- ]{6,}").find(t)?.value.orEmpty()
                confirmAction(
                    ActionConfirmationActivity.TYPE_CALL,
                    if (number.isBlank()) "בחר איש קשר לחיוג" else "חיוג למספר $number",
                    "",
                    phone = number
                )
            }
            t.contains("תזכורת") || t.contains("יומן") || t.contains("remind me", true) || t.contains("calendar", true) || t.contains("schedule", true) || t.contains("remind me", true) || t.contains("calendar", true) -> {
                val title = t.substringAfter("תזכורת", t.substringAfter("יומן", t)).trim()
                confirmAction(ActionConfirmationActivity.TYPE_CALENDAR, title.ifBlank { "תזכורת חדשה" }, "")
            }
            t.contains("חפש") || t.contains("גוגל") || t.contains("אינטרנט") || t.contains("search", true) || t.contains("google", true) || t.contains("look up", true) || t.contains("search", true) || t.contains("google", true) || t.contains("look up", true) -> {
                searchWeb(t.removePrefix("חפש").removePrefix(" לי").removeSuffix("באינטרנט").trim())
            }
            else -> {
                if (ErezAiBrain.isReady()) {
                    val answer = StringBuilder()
                    val previousLog = log.text.toString()
                    speak("קיבלתי. אני חושב על זה…")
                    ErezAiBrain.ask(
                        t,
                        { chunk ->
                            answer.append(chunk)
                            val partial = answer.toString()
                            runOnUiThread { log.text = previousLog + "\nארז: " + partial }
                        },
                        {
                            runOnUiThread {
                                val reply = answer.toString().trim()
                                speak(reply.ifBlank { "המודל סיים בלי להחזיר תשובה. אפשר לנסות שוב." })
                            }
                        },
                        { error -> runOnUiThread { speak("נתקלתי בבעיה ביצירת התשובה: $error") } }
                    )
                } else speak("The AI model is still loading or failed. Check the error shown on screen and tap Retry.")
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}