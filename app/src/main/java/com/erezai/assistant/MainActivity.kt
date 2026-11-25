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
import android.speech.RecognizerIntent
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
    private var tts: TextToSpeech? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == VoiceAssistantService.ACTION_RECOGNIZED) {
                intent.getStringExtra(VoiceAssistantService.EXTRA_COMMAND)?.let { command.setText(it); handle(it) }
            } else {
                latestScreenText = intent?.getStringExtra(ScreenCaptureService.EXTRA_SCREEN_TEXT)
                    ?: intent?.getStringExtra(ErezAccessibilityService.EXTRA_ACCESSIBILITY_TEXT).orEmpty()
                if (latestScreenText.isNotBlank()) say("זיהיתי במסך: " + latestScreenText.take(700))
            }
        }
    }

    private val screenConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val i = Intent(this, ScreenCaptureService::class.java)
                .putExtra("resultCode", result.resultCode).putExtra("data", result.data)
            ContextCompat.startForegroundService(this, i)
            say("שיתוף המסך הופעל. אני מנתח טקסט שמופיע בו. אפשר לעבור לאפליקציה אחרת; המחוון בהתראה יראה שהשיתוף פעיל.")
        } else say("לא הופעל שיתוף מסך.")
    }

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceService()
        else say("לא ניתן להפעיל שיחה קולית בלי הרשאת מיקרופון.")
    }

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val spoken = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) { command.setText(spoken); handle(spoken) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(ScreenCaptureService.ACTION_SCREEN_TEXT)
            addAction(ErezAccessibilityService.ACTION_ACCESSIBILITY_TEXT)
            addAction(VoiceAssistantService.ACTION_RECOGNIZED)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        super.onStop()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale("he", "IL")
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
        root.addView(button("שלח הוראה") { handle(command.text.toString()) })
        root.addView(button("דבר איתי") {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            try { speech.launch(i) } catch (_: Exception) { say("זיהוי דיבור בעברית אינו זמין במכשיר כרגע.") }
        })
        root.addView(button("הפעל שיחה קולית רציפה ברקע") { requestVoicePermissionAndStart() })
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
        root.addView(button("מה אתה רואה במסך?") {
            if (latestScreenText.isBlank()) say("עדיין לא התקבל טקסט מהמסך. הפעל קודם שיתוף מסך.")
            else speak("במסך אני מזהה: " + latestScreenText.take(1200))
        })
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
        try {
            ContextCompat.startForegroundService(this, Intent(this, VoiceAssistantService::class.java))
            say("הפעלתי האזנה קולית. המיקרופון פעיל כל עוד ההתראה של ארז AI מוצגת. אפשר לעצור דרך ההתראה או מתוך האפליקציה.")
        } catch (_: Exception) {
            say("לא הצלחתי להפעיל האזנה ברקע. יש לפתוח את האפליקציה ולאשר מיקרופון.")
        }
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
    private fun say(message: String) { if (::log.isInitialized) log.append("\n$message\n") }
    private fun speak(message: String) {
        say(message)
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "erez-ai-utterance")
    }

    private fun handle(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        say("אתה: $t")
        when {
            t.contains("מה אתה רואה") || t.contains("תאר את המסך") -> {
                if (latestScreenText.isBlank()) speak("אין לי כרגע טקסט מהמסך. הפעל שיתוף מסך.")
                else speak("במסך מופיע הטקסט הבא: " + latestScreenText.take(1200))
            }
            t.contains("וואטסאפ") || t.contains("whatsapp") -> {
                val msg = t.substringAfter("שלח", t).substringAfter("וואטסאפ", t).trim()
                val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg)
                try { startActivity(share.setPackage("com.whatsapp")) }
                catch (_: Exception) { startActivity(Intent.createChooser(share, "שליחה")) }
                say("פתחתי את מסך השיתוף. צריך לבחור איש קשר ולאשר שליחה.")
            }
            t.contains("מייל") || t.contains("אימייל") -> {
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                startActivity(Intent.createChooser(i, "בחירת אפליקציית דואר"))
                say("פתחתי אפליקציית דואר. השליחה דורשת אישור שלך.")
            }
            t.contains("התקשר") || t.contains("חייג") -> {
                val number = t.filter { it.isDigit() || it == '+' }
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
                say("פתחתי את החייגן. השיחה תתבצע רק לאחר לחיצה שלך.")
            }
            t.contains("תזכורת") || t.contains("יומן") -> {
                val i = Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
                    .putExtra(android.provider.CalendarContract.Events.TITLE, t)
                startActivity(i)
                say("פתחתי יצירת אירוע ביומן. יש להשלים ולאשר.")
            }
            t.contains("חפש") || t.contains("גוגל") || t.contains("אינטרנט") -> {
                val q = Uri.encode(t)
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q")))
                say("פתחתי תוצאות חיפוש בדפדפן. סיכום תשובות עם מקורות יתווסף בהמשך.")
            }
            else -> say("קיבלתי. הבנת שפה חופשית ותכנון פעולות עדיין בפיתוח.")
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}