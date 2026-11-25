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
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var log: TextView
    private lateinit var command: EditText
    private var latestScreenText = ""
    private var latestScreenImagePath = ""
    private var latestScreenFramePaths: List<String> = emptyList()
    private var tts: TextToSpeech? = null

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
            say("שיתוף המסך הופעל. אני מנתח תמונות וטקסט. אפשר לעבור לאפליקציה אחרת; ההתראה תציג שהשיתוף פעיל.")
        } else say("לא הופעל שיתוף מסך.")
    }

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceService()
        else say("לא ניתן להפעיל שיחה קולית בלי הרשאת מיקרופון.")
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) say("בלי הרשאת התראות, ייתכן שלא תראה את בקרת העצירה של שירותי הרקע.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
            tts?.language = Locale("he", "IL")
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
            text = "ארז AI"; textSize = 30f; setTextColor(-1)
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 18)
        }
        val subtitle = TextView(this).apply {
            text = "העוזר האישי שלך · עברית"; setTextColor(0xFFB7C4D8.toInt()); gravity = Gravity.CENTER
        }
        command = EditText(this).apply {
            hint = "כתוב לי מה לעשות…"; textSize = 17f; setTextColor(-1); setHintTextColor(0xFF9AA8BA.toInt())
        }
        log = TextView(this).apply {
            text = "שלום ארז. אני בתהליך בנייה.\n"; setTextColor(-1); textSize = 16f; setPadding(0, 20, 0, 20)
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(command)
        root.addView(button("הורד והפעל מוח AI מקומי כולל הבנת תמונות") {
            ErezAiBrain.prepare(this) { message -> runOnUiThread { say(message) } }
            say("התחלתי להכין את מודל הבינה במכשיר. ההורדה עשויה להיות גדולה.")
        })
        root.addView(button("שלח הוראה") { handle(command.text.toString()) })
        root.addView(button("הפעל שיחה קולית רציפה ברקע · עברית מקומית") { requestVoicePermissionAndStart() })
        root.addView(button("עצור שיחה קולית") { stopService(Intent(this, VoiceAssistantService::class.java)); say("בקשת עצירת ההאזנה נשלחה.") })
        root.addView(button("הצג עוזר צף מעל אפליקציות") {
            if (android.provider.Settings.canDrawOverlays(this)) {
                startService(Intent(this, OverlayAssistantService::class.java))
                say("העוזר הצף הופעל. אפשר לפתוח אותו גם בזמן שימוש באפליקציות אחרות.")
            } else {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                say("יש לאשר הצגה מעל אפליקציות. לאחר מכן חזור והפעל את העוזר הצף.")
            }
        })
        root.addView(button("אפשר לעוזר לצפות במסך") {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenConsent.launch(mgr.createScreenCaptureIntent())
        })
        root.addView(button("הפעל הבנת מסך גם בלי שיתוף מסך") {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            say("בהגדרות נגישות אפשר להפעיל את ארז AI. השירות יקרא טקסט גלוי באפליקציות רק לאחר שתפעיל אותו.")
        })
        root.addView(button("מה אתה רואה במסך?") { handle("מה אתה רואה במסך") })
        root.addView(button("עצור צפייה במסך") {
            stopService(Intent(this, ScreenCaptureService::class.java))
            latestScreenText = ""
            say("שיתוף המסך נעצר.")
        })
        root.addView(ScrollView(this).apply { addView(log) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun requestVoicePermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceService()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceService() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            say("כדי שהפקודות הקוליות יגיעו לעוזר הצף, יש לאשר תחילה הצגה מעל אפליקציות.")
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        try {
            startService(Intent(this, OverlayAssistantService::class.java))
            ContextCompat.startForegroundService(this, Intent(this, VoiceAssistantService::class.java))
            say("הפעלתי את שירות ההאזנה המקומי. לאחר טעינת מודל הדיבור, הפקודות יתחילו במילת ההפעלה ״ארז״. אפשר לעצור דרך ההתראה.")
        } catch (_: Exception) {
            say("לא הצלחתי להפעיל האזנה ברקע. יש לפתוח את האפליקציה ולאשר מיקרופון.")
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
                        "ענה בעברית על השאלה: " + query +
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
            t.contains("מה אתה רואה") || t.contains("תאר את המסך") -> {
                if (!ErezAiBrain.isReady()) {
                    speak("מוח הבינה המקומי עדיין לא נטען. יש להשלים את ההורדה הראשונית.")
                } else {
                    val snapshot = latestScreenImagePath.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
                    val answer = StringBuilder()
                    val prompt = "תאר בעברית מה מופיע במסך ומה קורה. השתמש בתמונות המסך עצמן ובטקסט שזוהה.\n" + latestScreenText.take(5000)
                    val onChunk: (String) -> Unit = { part -> answer.append(part) }
                    val onDone: () -> Unit = { runOnUiThread { speak(answer.toString().ifBlank { "הניתוח הסתיים." }) } }
                    val onError: (String) -> Unit = { error -> runOnUiThread { speak(error) } }
                    if (latestScreenFramePaths.size > 1) {
                        ErezAiBrain.analyzeFrames(prompt, latestScreenFramePaths, onChunk, onDone, onError)
                    } else {
                        ErezAiBrain.ask(prompt, onChunk, onDone, onError, snapshot)
                    }
                }
            }
            t.contains("תגובה") || t.contains("הגב") -> {
                if (!ErezAiBrain.isReady()) speak("כדי לנסח תגובה, צריך לטעון קודם את מוח הבינה המקומי.")
                else {
                    val draft = StringBuilder()
                    ErezAiBrain.ask(
                        "נסח תגובה בעברית טבעית וקצרה שמתאימה לפוסט או לסרטון שמופיעים במסך. החזר רק את נוסח התגובה. בקשת המשתמש: " +
                            t + "\\nתוכן המסך:\\n" + latestScreenText.take(5000),
                        { part -> draft.append(part) },
                        { runOnUiThread { confirmAction(ActionConfirmationActivity.TYPE_COMMENT, "טיוטת תגובה להעתקה", draft.toString()) } },
                        { error -> runOnUiThread { speak(error) } }
                    )
                }
            }
            t.contains("וואטסאפ") || t.contains("whatsapp") -> {
                val msg = t.substringAfter("שלח", t).substringAfter("וואטסאפ", t).trim()
                val phone = Regex("\\+?[0-9][0-9\\- ]{6,}").find(t)?.value.orEmpty()
                confirmAction(ActionConfirmationActivity.TYPE_WHATSAPP, "שליחת הודעת וואטסאפ", msg, phone = phone)
            }
            t.contains("מייל") || t.contains("אימייל") -> {
                val recipient = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").find(t)?.value.orEmpty()
                val subject = t.substringAfter("נושא", "הודעה מארז AI").substringBefore("תוכן").trim().ifBlank { "הודעה מארז AI" }
                if (ErezAiBrain.isReady()) {
                    val draft = StringBuilder()
                    ErezAiBrain.ask(
                        "נסח גוף של הודעת דואר בעברית לפי בקשת המשתמש. החזר רק את גוף ההודעה, בלי כותרות. בקשה: " + t,
                        { part -> draft.append(part) },
                        { runOnUiThread { confirmAction(ActionConfirmationActivity.TYPE_EMAIL, subject, draft.toString(), recipient = recipient) } },
                        { error -> runOnUiThread { speak(error) } }
                    )
                } else {
                    val body = t.substringAfter("תוכן", t.substringAfter("כתוב", t)).trim()
                    confirmAction(ActionConfirmationActivity.TYPE_EMAIL, subject, body, recipient = recipient)
                }
            }
            t.contains("התקשר") || t.contains("חייג") -> {
                val number = Regex("\\+?[0-9][0-9\\- ]{6,}").find(t)?.value.orEmpty()
                confirmAction(
                    ActionConfirmationActivity.TYPE_CALL,
                    if (number.isBlank()) "בחר איש קשר לחיוג" else "חיוג למספר $number",
                    "",
                    phone = number
                )
            }
            t.contains("תזכורת") || t.contains("יומן") -> {
                val title = t.substringAfter("תזכורת", t.substringAfter("יומן", t)).trim()
                confirmAction(ActionConfirmationActivity.TYPE_CALENDAR, title.ifBlank { "תזכורת חדשה" }, "")
            }
            t.contains("חפש") || t.contains("גוגל") || t.contains("אינטרנט") -> {
                searchWeb(t.removePrefix("חפש").removePrefix(" לי").removeSuffix("באינטרנט").trim())
            }
            else -> {
                if (ErezAiBrain.isReady()) {
                    speak("אני חושב…")
                    ErezAiBrain.ask(t, { chunk -> runOnUiThread { log.append(chunk) } },
                        { runOnUiThread { log.append("\\n") } },
                        { error -> runOnUiThread { speak(error) } })
                } else speak("מנוע הבינה עדיין לא מוכן. לחץ קודם על הורד והפעל מוח AI מקומי.")
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}