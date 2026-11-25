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
import java.util.Calendar

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
                        var digits = phone.filter { it.isDigit() }
                        if (digits.startsWith("0")) digits = "972" + digits.drop(1)
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
                    val date = Regex("(\\d{1,2})[./](\\d{1,2})[./](\\d{4})").find(title)
                    val time = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b").find(title)
                    if (date != null) {
                        val day = date.groupValues[1].toInt()
                        val month = date.groupValues[2].toInt() - 1
                        val year = date.groupValues[3].toInt()
                        val start = Calendar.getInstance().apply {
                            set(year, month, day, 9, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        if (time != null) {
                            start.set(Calendar.HOUR_OF_DAY, time.groupValues[1].toInt())
                            start.set(Calendar.MINUTE, time.groupValues[2].toInt())
                        }
                        val end = start.clone() as Calendar
                        end.add(Calendar.HOUR_OF_DAY, 1)
                        event.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.timeInMillis)
                        event.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.timeInMillis)
                    }
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
