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
    @Volatile private var offlineHebrewTts: OfflineHebrewTts? = null
    @Volatile private var offlineHebrewLoading = false
    @Volatile private var pendingHebrewSpeech = ""
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
                    statusText?.text = "Voice recognized: $transcript"
                }
            } else if (intent?.action == VoiceAssistantService.ACTION_STATUS) {
                latestVoiceStatus = intent.getStringExtra(VoiceAssistantService.EXTRA_STATUS).orEmpty()
                voiceListening = latestVoiceStatus.contains("Listening", ignoreCase = true) || latestVoiceStatus.contains("standby", ignoreCase = true) || latestVoiceStatus.contains("speech model", ignoreCase = true) || latestVoiceStatus.contains("Preparing", ignoreCase = true)
                statusText?.text = latestVoiceStatus
                voiceButton?.text = if (voiceListening) "⏹ Stop listening" else "🎙️ Voice standby"
            } else if (intent?.action == ScreenCaptureService.ACTION_SCREEN_STOPPED) {
                latestText = ""
                latestScreenImagePath = ""
                latestScreenFramePaths = emptyList()
                statusText?.text = "Screen sharing stopped."
            } else if (intent?.action == ScreenCaptureService.ACTION_SCREEN_CAPTURE_DONE) {
                statusText?.text = "One-shot screen capture complete."
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
            addAction(ScreenCaptureService.ACTION_SCREEN_CAPTURE_DONE)
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
            else if (!ttsReady) statusText?.text = "English speech data is not installed. Install an English voice in Android text-to-speech settings."
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
        val containsHebrew = message.any { it.code in 0x0590..0x05FF }
        if (tts == null || !ttsReady) {
            if (containsHebrew) speakHebrewOffline(message) else pendingSpeech = message
            return
        }
        val requestedLocale = if (containsHebrew) Locale("he", "IL") else Locale.US
        val languageResult = tts?.setLanguage(requestedLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (languageResult < TextToSpeech.LANG_AVAILABLE) {
            if (containsHebrew) {
                speakHebrewOffline(message)
            } else {
                statusText?.text = "English speech data is not installed on this device."
                voiceControl(VoiceAssistantService.ACTION_RESUME)
            }
            return
        }
        voiceControl(VoiceAssistantService.ACTION_PAUSE)
        val result = tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "erez-ai-background")
        if (result == TextToSpeech.ERROR) voiceControl(VoiceAssistantService.ACTION_RESUME)
    }

    private fun speakHebrewOffline(message: String) {
        pendingHebrewSpeech = message
        voiceControl(VoiceAssistantService.ACTION_PAUSE)
        if (offlineHebrewTts != null) {
            Thread {
                try { offlineHebrewTts?.speak(message) }
                catch (e: Exception) { Handler(Looper.getMainLooper()).post { statusText?.text = "Hebrew speech failed: ${e.message ?: "unknown error"}" } }
                finally { voiceControl(VoiceAssistantService.ACTION_RESUME) }
            }.start()
            return
        }
        if (offlineHebrewLoading) return
        offlineHebrewLoading = true
        statusText?.text = "Preparing the offline Hebrew voice for the first time…"
        Thread {
            try {
                val engine = OfflineHebrewTts(applicationContext)
                engine.initialize { update -> Handler(Looper.getMainLooper()).post { statusText?.text = update } }
                offlineHebrewTts = engine
                val textToSpeak = pendingHebrewSpeech
                pendingHebrewSpeech = ""
                if (textToSpeak.isNotBlank()) engine.speak(textToSpeak)
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { statusText?.text = "Offline Hebrew voice could not start: ${e.message ?: "unknown error"}" }
            } finally {
                offlineHebrewLoading = false
                voiceControl(VoiceAssistantService.ACTION_RESUME)
            }
        }.start()
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
            contentDescription = "Open Erez AI"
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
            button.text = "🎙️ Wake-word standby"
            speak("Wake-word listening stopped.")
            return
        }
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            speak("Please grant microphone access in Android settings.")
            return
        }
        try {
            val intent = Intent(this, VoiceAssistantService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            voiceListening = true
            button.text = "⏹ Stop listening"
            speak("Wake-word standby is active. Say Hey Erez.")
        } catch (e: Exception) {
            speak("Could not start the microphone: ${e.message ?: "unknown error"}")
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
            text = "✨  Erez AI"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(8))
        }
        val subtitle = TextView(this).apply {
            text = "Say Hey Erez or type a command"
            textSize = 13f
            setTextColor(0xFFB9C7E8.toInt())
            setPadding(0, 0, 0, dp(12))
        }
        val input = EditText(this).apply {
            hint = "What would you like me to do?"
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
                else -> "Ready in wake-word standby."
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
        val mic = styledButton(if (voiceListening) "⏹ Stop listening" else "🎙️ Wake-word standby", 0xFF7C3AED.toInt()) {
            toggleVoice(it as TextView)
        }
        voiceButton = mic
        actions.addView(mic, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        actions.addView(styledButton("Send  ➤", 0xFF2563EB.toInt()) {
            runCommand(input.text.toString())
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })

        val secondary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        secondary.addView(styledButton("Read screen", 0xFF0F766E.toInt()) {
            runCommand("Read the screen")
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        secondary.addView(styledButton("Close", 0xFF475569.toInt()) {
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
        statusText?.text = "I heard: $command"
        val lower = command.lowercase(Locale.US)
        when {
            lower.contains("read this") || lower.contains("read the screen") ||
                lower.contains("read it aloud") || lower.contains("read this to me") -> {
                if (latestText.isNotBlank()) {
                    // Preserve the source language. Hebrew text is passed to the
                    // Hebrew TTS voice rather than translated into English.
                    speak(latestText.take(3500))
                } else if (latestScreenImagePath.isNotBlank() && ErezAiBrain.isReady()) {
                    val snapshot = java.io.File(latestScreenImagePath)
                    askAi(
                        "Transcribe all readable text visible in this image exactly as written. Preserve every original language and script, especially Hebrew. Do not translate, summarize, or transliterate. Return only the transcription.",
                        snapshot
                    )
                } else {
                    requestScreenCapture()
                }
            }
            lower.contains("what is on my screen") || lower.contains("what do you see") ||
                lower.contains("describe my screen") || lower.contains("describe the screen") -> {
                if (latestText.isBlank() && latestScreenImagePath.isBlank()) {
                    requestScreenCapture()
                } else if (ErezAiBrain.isReady()) {
                    val snapshot = latestScreenImagePath.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
                    askAi(
                        "Describe in concise, natural English what is visible on the phone screen. You may quote visible Hebrew text exactly in Hebrew; do not translate quoted text unless asked.\n" +
                            latestText.take(5000),
                        snapshot
                    )
                } else {
                    speak("The local AI brain is not ready yet.")
                }
            }
            lower.contains("comment") || lower.contains("write a reply") || lower.contains("draft a reply") -> {
                if (!ErezAiBrain.isReady()) {
                    speak("The local AI brain is not ready yet.")
                } else {
                    val context = (latestText + "\n" + latestAudioText).take(7000)
                    val draft = StringBuilder()
                    speak("Drafting a reply based on the content on your screen.")
                    ErezAiBrain.ask(
                        "Write a concise, natural English comment or reply suitable for the visible post or video. Do not invent facts. Return only the draft. User request: " +
                            command + "\nVisible screen text and nearby audio transcript:\n" + context,
                        { part -> draft.append(part) },
                        {
                            Handler(Looper.getMainLooper()).post {
                                confirmAction(ActionConfirmationActivity.TYPE_COMMENT, "Comment draft", draft.toString())
                            }
                        },
                        { error -> Handler(Looper.getMainLooper()).post { speak(error) } }
                    )
                }
            }
            lower.contains("whatsapp") -> {
                speak("WhatsApp voice-note replies are not connected yet. I will not send a typed message instead.")
            }
            lower.contains("call ") || lower.startsWith("call") || lower.contains("dial ") -> {
                val number = Regex("\\+?[0-9][0-9\\- ]{6,}").find(command)?.value.orEmpty()
                confirmAction(
                    ActionConfirmationActivity.TYPE_CALL,
                    if (number.isBlank()) "Choose a contact to call" else "Call $number",
                    "",
                    phone = number
                )
            }
            lower.contains("email") || lower.contains("e-mail") -> {
                val recipient = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").find(command)?.value.orEmpty()
                val subject = command.substringAfter("subject", "Message from Erez AI").substringBefore("body").trim()
                    .ifBlank { "Message from Erez AI" }
                if (ErezAiBrain.isReady()) {
                    val draft = StringBuilder()
                    ErezAiBrain.ask(
                        "Write an email body in natural English based on this request. Return only the body. Request: " + command,
                        { part -> draft.append(part) },
                        { Handler(Looper.getMainLooper()).post {
                            confirmAction(ActionConfirmationActivity.TYPE_EMAIL, subject, draft.toString(), recipient = recipient)
                        } },
                        { error -> Handler(Looper.getMainLooper()).post { speak(error) } }
                    )
                } else {
                    val body = command.substringAfter("body", command).trim()
                    confirmAction(ActionConfirmationActivity.TYPE_EMAIL, subject, body, recipient = recipient)
                }
            }
            lower.contains("remind me") || lower.contains("calendar") || lower.contains("reminder") -> {
                val title = command.substringAfter("remind me", command.substringAfter("reminder", command)).trim()
                confirmAction(ActionConfirmationActivity.TYPE_CALENDAR, title.ifBlank { command }, "")
            }
            lower.startsWith("search ") || lower.contains("search the web") || lower.contains("look up ") -> {
                val query = command.removePrefix("search").removePrefix("the web for").removePrefix("look up").trim()
                searchWeb(query.ifBlank { command })
            }
            else -> {
                if (ErezAiBrain.isReady()) {
                    val context = if (latestText.isBlank()) "" else
                        "\n\nVisible phone screen text (may be partial; preserve source language):\n" + latestText.take(5000)
                    askAi("Answer in natural English unless I explicitly ask you to read source text aloud in its original language. " + command + context)
                } else {
                    speak("The local AI brain is still loading. Please wait until setup finishes.")
                }
            }
        }
    }

    private fun requestScreenCapture() {
        speak("Android needs your approval before I can view the screen. Please approve the screen-sharing prompt.")
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_REQUEST_SCREEN_CAPTURE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }

    private fun searchWeb(query: String) {
        speak("Searching the web.")
        Thread {
            try {
                val results = FreeWebSearch.search(query)
                if (results.isEmpty()) {
                    Handler(Looper.getMainLooper()).post { speak("No search results were returned. Try a different query.") }
                    return@Thread
                }
                val sourceText = results.mapIndexed { index, result ->
                    "${index + 1}. ${result.title}\n${result.snippet}\nLink: ${result.url}"
                }.joinToString("\n\n")
                if (!ErezAiBrain.isReady()) {
                    Handler(Looper.getMainLooper()).post {
                        statusText?.text = sourceText
                        speak("Found ${results.size} results. The local AI brain is needed for a summary.")
                    }
                    return@Thread
                }
                val answer = StringBuilder()
                ErezAiBrain.ask(
                    "Answer in natural English: " + query +
                        "\nUse only the information below. State uncertainty and include the supplied links unchanged.\n\n" + sourceText,
                    { part -> answer.append(part) },
                    {
                        Handler(Looper.getMainLooper()).post {
                            statusText?.text = answer.toString() + "\n\nSources:\n" + results.joinToString("\n") { it.title + " — " + it.url }
                            speak(answer.toString().take(900))
                        }
                    },
                    { error -> Handler(Looper.getMainLooper()).post { speak(error) } }
                )
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    speak("Search failed: ${e.message ?: "network error"}.")
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
        speak("Analyzing the content…")
        val onChunk: (String) -> Unit = { chunk ->
            answer.append(chunk)
            val partial = answer.toString()
            Handler(Looper.getMainLooper()).post { statusText?.text = partial }
        }
        val onDone: () -> Unit = {
            Handler(Looper.getMainLooper()).post {
                val reply = answer.toString().trim()
                speak(reply.ifBlank { "The model returned an empty response. Please try again." })
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
        offlineHebrewTts?.close()
        offlineHebrewTts = null
        bubble?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}