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
import java.util.Locale

class OverlayAssistantService : Service(), TextToSpeech.OnInitListener {
    private lateinit var windowManager: WindowManager
    private var bubble: Button? = null
    private var panel: LinearLayout? = null
    private var latestText = ""
    private var statusText: TextView? = null
    private var tts: TextToSpeech? = null
    private val textReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == VoiceAssistantService.ACTION_RECOGNIZED) {
                intent.getStringExtra(VoiceAssistantService.EXTRA_COMMAND)?.let { runCommand(it) }
            } else {
                latestText = intent?.getStringExtra(ScreenCaptureService.EXTRA_SCREEN_TEXT)
                    ?: intent?.getStringExtra(ErezAccessibilityService.EXTRA_ACCESSIBILITY_TEXT).orEmpty()
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
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(textReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(textReceiver, filter)
        showBubble()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale("he", "IL")
    }

    private fun speak(message: String) {
        statusText?.text = message
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "erez-ai-background")
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
            setOnClickListener {
                status.text = if (latestText.isBlank()) "אין עדיין טקסט מהמסך. הפעל שיתוף מסך או שירות נגישות." else latestText.take(1200)
            }
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
                if (latestText.isBlank()) {
                    speak("אין כרגע תוכן טקסטואלי מהמסך. אפשר להפעיל שיתוף מסך או נגישות.")
                } else if (ErezAiBrain.isReady()) {
                    askAi("נתח וסכם בעברית את התוכן הגלוי במסך. ציין מה קורה ומה חשוב לדעת.\n" + latestText.take(5000))
                } else {
                    speak("זיהיתי טקסט במסך, אבל מנוע הבינה עדיין לא נטען: " + latestText.take(800))
                }
            }
            command.contains("וואטסאפ") || command.contains("WhatsApp", true) -> {
                val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, command.substringAfter("שלח", command))
                share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { startActivity(share.setPackage("com.whatsapp")) }
                catch (_: Exception) { startActivity(Intent.createChooser(share, "שליחה").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                speak("פתחתי את וואטסאפ. בחר איש קשר ואשר את השליחה בעצמך.")
            }
            command.contains("התקשר") || command.contains("חייג") -> {
                val number = command.filter { it.isDigit() || it == '+' }
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                speak("פתחתי את החייגן. לחץ על כפתור השיחה כדי להתקשר.")
            }
            command.contains("מייל") || command.contains("אימייל") -> {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                speak("פתחתי את אפליקציית הדואר. אפשר לנסח ולאשר את השליחה.")
            }
            command.contains("תזכורת") || command.contains("יומן") -> {
                val event = Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
                    .putExtra(android.provider.CalendarContract.Events.TITLE, command)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(event)
                speak("פתחתי אירוע חדש ביומן. השלם את הפרטים ושמור.")
            }
            command.contains("חפש") || command.contains("אינטרנט") -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(command)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                speak("פתחתי חיפוש באינטרנט.")
            }
            else -> {
                if (ErezAiBrain.isReady()) {
                    val context = if (latestText.isBlank()) "" else "\n\nתוכן גלוי כרגע במסך (ייתכן שהוא חלקי):\n" + latestText.take(5000)
                    askAi(command + context)
                } else speak("מנוע הבינה עדיין לא מוכן. פתח את האפליקציה הראשית ולחץ על הורד והפעל מוח AI מקומי.")
            }
        }
    }

    private fun askAi(prompt: String) {
        val answer = StringBuilder()
        speak("אני חושב…")
        ErezAiBrain.ask(prompt,
            { chunk -> answer.append(chunk) },
            { Handler(Looper.getMainLooper()).post { speak(answer.toString().ifBlank { "לא התקבלה תשובה." }) } },
            { error -> Handler(Looper.getMainLooper()).post { speak(error) } }
        )
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