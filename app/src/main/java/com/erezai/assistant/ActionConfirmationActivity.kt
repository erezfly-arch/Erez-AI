package com.erezai.assistant

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Explicit confirmation gate before handing a consequential action to another app.
 * The target app still owns its final Send/Call/Save confirmation.
 */
class ActionConfirmationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val type = intent.getStringExtra(EXTRA_TYPE).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val recipient = intent.getStringExtra(EXTRA_RECIPIENT).orEmpty()
        val phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 36, 28, 36)
            setBackgroundColor(0xFF101827.toInt())
        }
        root.addView(TextView(this).apply {
            text = "אישור פעולה · ארז AI"
            textSize = 24f
            setTextColor(-1)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = title + if (recipient.isNotBlank()) "\nאל: $recipient" else ""
            textSize = 18f
            setTextColor(-1)
            setPadding(0, 24, 0, 12)
        })
        if (body.isNotBlank()) root.addView(TextView(this).apply {
            text = body
            textSize = 16f
            setTextColor(0xFFE0E7F0.toInt())
            setPadding(0, 0, 0, 24)
        })
        root.addView(Button(this).apply {
            text = "אשר והמשך"
            setOnClickListener { executeAction(type, title, body, recipient, phone) }
        })
        root.addView(Button(this).apply {
            text = "ביטול"
            setOnClickListener { finish() }
        })
        setContentView(root)
    }

    private fun executeAction(type: String, title: String, body: String, recipient: String, phone: String) {
        try {
            when (type) {
                TYPE_WHATSAPP -> {
                    if (phone.filter { it.isDigit() }.length >= 8) {
                        val digits = phone.filter { it.isDigit() }
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=" + Uri.encode(body))))
                    } else {
                        val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, body)
                        try { startActivity(share.setPackage("com.whatsapp")) }
                        catch (_: Exception) { startActivity(Intent.createChooser(share, "שליחה בוואטסאפ")) }
                    }
                }
                TYPE_EMAIL -> {
                    val uri = Uri.parse("mailto:" + Uri.encode(recipient) +
                        "?subject=" + Uri.encode(title) + "&body=" + Uri.encode(body))
                    startActivity(Intent(Intent.ACTION_SENDTO, uri))
                }
                TYPE_CALL -> {
                    if (phone.isBlank()) throw IllegalArgumentException("לא זוהה מספר טלפון")
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone))))
                }
                TYPE_CALENDAR -> {
                    val event = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, title)
                        .putExtra(CalendarContract.Events.DESCRIPTION, body)
                    startActivity(event)
                }
                TYPE_COMMENT -> {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("תגובה", body))
                    Toast.makeText(this, "התגובה הועתקה. אפשר להדביק אותה בשדה התגובה.", Toast.LENGTH_LONG).show()
                }
                else -> throw IllegalArgumentException("סוג פעולה לא מוכר")
            }
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "לא ניתן לפתוח את הפעולה", Toast.LENGTH_LONG).show()
        } finally {
            finish()
        }
    }

    companion object {
        const val EXTRA_TYPE = "action_type"
        const val EXTRA_TITLE = "action_title"
        const val EXTRA_BODY = "action_body"
        const val EXTRA_RECIPIENT = "action_recipient"
        const val EXTRA_PHONE = "action_phone"
        const val TYPE_WHATSAPP = "whatsapp"
        const val TYPE_EMAIL = "email"
        const val TYPE_CALL = "call"
        const val TYPE_CALENDAR = "calendar"
        const val TYPE_COMMENT = "comment"
    }
}
