package com.erezai.assistant

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
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
    private var selectedPhone = ""
    private var confirmButton: Button? = null
    private var detailText: TextView? = null
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
            text = "Action confirmation · Erez AI"
            textSize = 24f
            setTextColor(-1)
            gravity = Gravity.CENTER
        })
        detailText = TextView(this).apply {
            text = title + if (recipient.isNotBlank()) "\nTo: $recipient" else ""
            textSize = 18f
            setTextColor(-1)
            setPadding(0, 24, 0, 12)
        }
        root.addView(detailText)
        if (body.isNotBlank()) root.addView(TextView(this).apply {
            text = body
            textSize = 16f
            setTextColor(0xFFE0E7F0.toInt())
            setPadding(0, 0, 0, 24)
        })
        confirmButton = Button(this).apply {
            text = if (type == TYPE_CALL && phone.isBlank()) "Choose contact" else "Confirm and continue"
            setOnClickListener {
                if (type == TYPE_CALL && phone.isBlank() && selectedPhone.isBlank()) {
                    try {
                        startActivityForResult(
                            Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
                            REQUEST_PHONE
                        )
                    } catch (_: Exception) {
                        Toast.makeText(this@ActionConfirmationActivity, "Could not open contacts", Toast.LENGTH_LONG).show()
                    }
                } else {
                    executeAction(type, title, body, recipient, selectedPhone.ifBlank { phone })
                }
            }
        }
        root.addView(confirmButton)
        root.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        })
        setContentView(root)
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PHONE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val column = ContactsContract.CommonDataKinds.Phone.NUMBER
        contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) selectedPhone = cursor.getString(0).orEmpty()
        }
        if (selectedPhone.isNotBlank()) {
            detailText?.text = "Selected contact\n$selectedPhone"
            confirmButton?.text = "Confirm and continue"
        } else {
            Toast.makeText(this, "No phone number found for this contact", Toast.LENGTH_LONG).show()
        }
    }

    private fun executeAction(type: String, title: String, body: String, recipient: String, phone: String) {
        try {
            when (type) {
                TYPE_EMAIL -> {
                    val uri = Uri.parse("mailto:" + Uri.encode(recipient) +
                        "?subject=" + Uri.encode(title) + "&body=" + Uri.encode(body))
                    startActivity(Intent(Intent.ACTION_SENDTO, uri))
                }
                TYPE_CALL -> {
                    if (phone.isBlank()) throw IllegalArgumentException("No phone number was detected")
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone))))
                }
                TYPE_CALENDAR -> {
                    val event = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, title)
                        .putExtra(CalendarContract.Events.DESCRIPTION, body)
                    val date = Regex("(\\d{1,2})[./](\\d{1,2})[./](\\d{4})").find(title)
                    val time = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b").find(title)
                    val hasRelativeDate = title.contains("tomorrow", ignoreCase = true) || title.contains("today", ignoreCase = true)
                    if (date != null || hasRelativeDate) {
                        val start = Calendar.getInstance()
                        if (date != null) {
                            start.set(
                                date.groupValues[3].toInt(),
                                date.groupValues[2].toInt() - 1,
                                date.groupValues[1].toInt(),
                                9, 0, 0
                            )
                        } else if (title.contains("tomorrow", ignoreCase = true)) {
                            start.add(Calendar.DAY_OF_YEAR, 1)
                        }
                        if (time != null) {
                            start.set(Calendar.HOUR_OF_DAY, time.groupValues[1].toInt())
                            start.set(Calendar.MINUTE, time.groupValues[2].toInt())
                        } else if (date == null && title.contains("today", ignoreCase = true)) {
                            start.add(Calendar.MINUTE, 15)
                        } else {
                            start.set(Calendar.HOUR_OF_DAY, 9)
                            start.set(Calendar.MINUTE, 0)
                        }
                        start.set(Calendar.SECOND, 0)
                        start.set(Calendar.MILLISECOND, 0)
                        val end = start.clone() as Calendar
                        end.add(Calendar.HOUR_OF_DAY, 1)
                        event.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.timeInMillis)
                        event.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.timeInMillis)
                    }
                    startActivity(event)
                }
                TYPE_COMMENT -> {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Comment", body))
                    Toast.makeText(this, "Comment copied. You can paste it into the comment field.", Toast.LENGTH_LONG).show()
                }
                else -> throw IllegalArgumentException("Unknown action type")
            }
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Could not open this action", Toast.LENGTH_LONG).show()
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
        const val TYPE_EMAIL = "email"
        const val TYPE_CALL = "call"
        const val TYPE_CALENDAR = "calendar"
        const val TYPE_COMMENT = "comment"
        private const val REQUEST_PHONE = 721
    }
}
