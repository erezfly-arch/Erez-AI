package com.erezai.assistant

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var log: TextView
    private lateinit var command: EditText
    private val screenConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val i = Intent(this, ScreenCaptureService::class.java).putExtra("resultCode", result.resultCode).putExtra("data", result.data)
            ContextCompat.startForegroundService(this, i)
            say("שיתוף המסך הופעל באישור שלך. שלב ניתוח התוכן עדיין בפיתוח.")
        } else say("לא הופעל שיתוף מסך.")
    }
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val spoken = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) { command.setText(spoken); handle(spoken) }
    }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); buildUi() }
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,32,24,24); setBackgroundColor(0xFF101827.toInt()) }
        val title = TextView(this).apply { text = "ארז AI"; textSize = 30f; setTextColor(-1); gravity = Gravity.CENTER; setPadding(0,0,0,18) }
        val subtitle = TextView(this).apply { text = "העוזר האישי שלך · עברית"; setTextColor(0xFFB7C4D8.toInt()); gravity = Gravity.CENTER }
        command = EditText(this).apply { hint = "כתוב לי מה לעשות…"; textSize = 17f; setTextColor(-1); setHintTextColor(0xFF9AA8BA.toInt()) }
        log = TextView(this).apply { text = "שלום ארז. זו סביבת הפיתוח של העוזר.\n"; setTextColor(-1); textSize = 16f; setPadding(0,20,0,20) }
        root.addView(title); root.addView(subtitle); root.addView(command)
        root.addView(button("שלח הוראה") { handle(command.text.toString()) })
        root.addView(button("דבר איתי") { val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL").putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); speech.launch(i) })
        root.addView(button("אפשר לעוזר לצפות במסך") { val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager; screenConsent.launch(mgr.createScreenCaptureIntent()) })
        root.addView(button("עצור צפייה במסך") { stopService(Intent(this, ScreenCaptureService::class.java)); say("שיתוף המסך נעצר.") })
        root.addView(ScrollView(this).apply { addView(log) }, LinearLayout.LayoutParams(-1,0,1f) }
        setContentView(root)
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
    private fun say(message: String) { log.append("\n$message\n") }
    private fun handle(raw: String) {
        val t = raw.trim(); if (t.isEmpty()) return
        say("אתה: $t")
        when {
            t.contains("וואטסאפ") || t.contains("whatsapp") -> { val msg = t.substringAfter("תגיד", "").substringAfter("שלח", t); val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg).setPackage("com.whatsapp"); try { startActivity(i) } catch (_: Exception) { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,msg), "שליחה")) }; say("פתחתי את מסך השיתוף. צריך לבחור איש קשר ולאשר שליחה.") }
            t.contains("מייל") || t.contains("אימייל") -> { val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")); i.putExtra(Intent.EXTRA_SUBJECT, ""); startActivity(Intent.createChooser(i, "בחירת אפליקציית דואר")); say("פתחתי אפליקציית דואר. השליחה דורשת אישור שלך.") }
            t.contains("התקשר") || t.contains("חייג") -> { val number = t.filter { it.isDigit() || it == '+' }; startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))); say("פתחתי את החייגן. השיחה תתבצע רק לאחר לחיצה שלך.") }
            t.contains("תזכורת") || t.contains("יומן") -> { val i = Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI).putExtra(android.provider.CalendarContract.Events.TITLE, t); startActivity(i); say("פתחתי יצירת אירוע ביומן. יש להשלים ולאשר.") }
            t.contains("חפש") || t.contains("גוגל") || t.contains("אינטרנט") -> { val q = Uri.encode(t); startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q"))); say("פתחתי תוצאות חיפוש בדפדפן. תשובות מסוכמות עדיין בפיתוח.") }
            else -> say("קיבלתי. הבנת שפה חופשית, ניתוח מסך ופעולות אוטומטיות מלאות עדיין בפיתוח.")
        }
    }
}