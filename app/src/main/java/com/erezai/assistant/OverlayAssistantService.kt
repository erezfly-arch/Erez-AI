package com.erezai.assistant

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class OverlayAssistantService : Service() {
    private lateinit var windowManager: WindowManager
    private var bubble: Button? = null
    private var panel: LinearLayout? = null
    private var latestText = ""

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
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
    }

    private fun runCommand(raw: String) {
        val command = raw.trim()
        if (command.isBlank()) return
        when {
            command.contains("וואטסאפ") || command.contains("WhatsApp", true) -> {
                val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, command.substringAfter("שלח", command))
                share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { startActivity(share.setPackage("com.whatsapp")) }
                catch (_: Exception) { startActivity(Intent.createChooser(share, "שליחה").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            command.contains("התקשר") || command.contains("חייג") -> {
                val number = command.filter { it.isDigit() || it == '+' }
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            command.contains("מייל") || command.contains("אימייל") -> {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            command.contains("תזכורת") || command.contains("יומן") -> {
                val event = Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
                    .putExtra(android.provider.CalendarContract.Events.TITLE, command)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(event)
            }
            command.contains("חפש") || command.contains("אינטרנט") -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(command)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    override fun onDestroy() {
        hidePanel()
        bubble?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}