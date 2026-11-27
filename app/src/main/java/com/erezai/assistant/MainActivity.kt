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
    companion object {
        const val EXTRA_REQUEST_SCREEN_CAPTURE = "request_screen_capture"
        const val EXTRA_SCREEN_CAPTURE_ONE_SHOT = "screen_capture_one_shot"
    }

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
    private var screenCaptureOneShot = true
    private var voiceEnrollmentLaunched = false
    private var pendingCommandAfterBrain = ""

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenCaptureService.ACTION_SCREEN_STOPPED) {
                latestScreenText = ""
                latestScreenImagePath = ""
                latestScreenFramePaths = emptyList()
                screenConsentRequested = false
                say("Screen sharing stopped.")
            } else if (intent?.action == ScreenCaptureService.ACTION_SCREEN_CAPTURE_DONE) {
                screenConsentRequested = false
                say("One-shot screen capture complete.")
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
                .putExtra("resultCode", result.resultCode)
                .putExtra("data", result.data)
                .putExtra(ScreenCaptureService.EXTRA_ONE_SHOT, screenCaptureOneShot)
            moveTaskToBack(true)
            ContextCompat.startForegroundService(this, i)
            say("Screen sharing is active. I can analyze visible images and text while you use other apps. The notification shows when sharing is active.")
            startVoiceService()
        } else {
            screenConsentRequested = false
            sendBroadcast(Intent(ScreenCaptureService.ACTION_SCREEN_CAPTURE_FAILED).setPackage(packageName))
            say("Screen sharing was not approved. Voice standby can continue, but I cannot see your screen.")
            startVoiceService()
        }
    }

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) ensureOverlayAndStartVoice()
        else say("Erez needs microphone permission to listen and speak. You can grant it in Android settings.")
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) say("Notifications were not enabled. Continuing Erez AI setup.")
        requestMicrophonePermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        buildUi()
        // First-run setup is automatic: prepare the local AI model and request
        // the minimum permissions in sequence instead of asking the user to find
        // separate download/start buttons.
        say("Erez AI is ready for Hey Erez. The larger reasoning model loads only when a task needs it.")
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestMicrophonePermission()
        }
        if (intent?.getBooleanExtra(EXTRA_REQUEST_SCREEN_CAPTURE, false) == true) {
            screenCaptureOneShot = intent.getBooleanExtra(EXTRA_SCREEN_CAPTURE_ONE_SHOT, true)
            intent.removeExtra(EXTRA_REQUEST_SCREEN_CAPTURE)
            window.decorView.post { requestScreenCaptureConsent() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REQUEST_SCREEN_CAPTURE, false)) {
            screenConsentRequested = false
            screenCaptureOneShot = intent.getBooleanExtra(EXTRA_SCREEN_CAPTURE_ONE_SHOT, true)
            intent.removeExtra(EXTRA_REQUEST_SCREEN_CAPTURE)
            requestScreenCaptureConsent()
        }
    }
    override fun onResume() {
        super.onResume()
        if (voiceEnrollmentLaunched) {
            voiceEnrollmentLaunched = false
            if (VoiceIdentity.hasEnrollment(this)) startVoiceService()
            else say("Voice profile setup was not completed. Use the voice-profile button to try again.")
        }
        if (pendingVoiceStart && android.provider.Settings.canDrawOverlays(this)) {
            pendingVoiceStart = false
            startVoiceService()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(ScreenCaptureService.ACTION_SCREEN_TEXT)
            addAction(ErezAccessibilityService.ACTION_ACCESSIBILITY_TEXT)
            addAction(ScreenCaptureService.ACTION_SCREEN_STOPPED)
            addAction(ScreenCaptureService.ACTION_SCREEN_CAPTURE_DONE)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        super.onStop()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            val englishVoice = tts?.voices?.firstOrNull { voice ->
                voice.locale.language == "en" &&
                    (voice.name.contains("male", ignoreCase = true) || voice.name.contains("masculine", ignoreCase = true))
            }
            if (englishVoice != null) tts?.voice = englishVoice
            tts?.setPitch(1.0f)
            if (englishVoice == null) say("No dedicated English male voice was found. Using the installed English voice.")
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
            text = "Your personal AI assistant · English"; setTextColor(0xFFB7C4D8.toInt()); gravity = Gravity.CENTER
        }
        command = EditText(this).apply {
            hint = "Tell me what to do…"; textSize = 17f; setTextColor(-1); setHintTextColor(0xFF9AA8BA.toInt())
        }
        log = TextView(this).apply {
            text = "Erez AI is preparing its local brain and wake-word standby.\n"
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
            text = "Wake-word detection and speaker verification run locally. The larger reasoning model loads only when needed."
            setTextColor(0xFFB7C4D8.toInt())
            textSize = 14f
            setPadding(0, 12, 0, 12)
        })
        root.addView(button("Send command") { handle(command.text.toString()) })
        root.addView(button("Set up / retrain voice profile") { startVoiceEnrollment() })
        modelRetryButton = button("Retry AI engine") { prepareBrain() }.apply { visibility = android.view.View.GONE }
        root.addView(modelRetryButton)
        root.addView(TextView(this).apply {
            text = "Wake-word standby stays on after setup. Screen capture is not continuous; Android permission is required when visual analysis is requested. Stop listening from the Erez AI notification."
            setTextColor(0xFFB7C4D8.toInt())
            textSize = 13f
            setPadding(0, 10, 0, 10)
        })
        root.addView(ScrollView(this).apply { addView(log) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun prepareBrain() {
        if (ErezAiBrain.isReady()) {
            say("The AI engine is ready.")
            return
        }
        if (::modelRetryButton.isInitialized) {
            modelRetryButton.isEnabled = false
            modelRetryButton.text = "Loading AI engine…"
            modelRetryButton.visibility = android.view.View.VISIBLE
        }
        ErezAiBrain.prepare(this) { message ->
            runOnUiThread {
                say(message)
                val failed = message.startsWith("AI engine failed to load")
                val ready = message.contains("Local AI engine is ready")
                if (::modelRetryButton.isInitialized) {
                    modelRetryButton.visibility = if (failed) android.view.View.VISIBLE else android.view.View.GONE
                    modelRetryButton.isEnabled = failed
                    modelRetryButton.text = if (failed) "Loading failed — retry" else if (ready) "AI engine ready" else "Loading AI engine…"
                }
                if (ready && pendingCommandAfterBrain.isNotBlank()) {
                    val pending = pendingCommandAfterBrain
                    pendingCommandAfterBrain = ""
                    handle(pending)
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
            say("One-time permission is required to display Erez over other apps. Open Android permission settings and approve; Erez will continue automatically when you return.")
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        // Start in wake-word standby. Do not capture the screen continuously:
        // Android screen projection is requested only when the user explicitly asks
        // for visual screen analysis, reducing memory, battery, and privacy exposure.
        startVoiceService()
    }

    private fun requestScreenCaptureConsent() {
        if (screenConsentRequested) return
        screenConsentRequested = true
        say("Android requires one-time consent for screen sharing. Approve the system prompt so Erez can understand what is shown in apps.")
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenConsent.launch(mgr.createScreenCaptureIntent())
    }

    private fun startVoiceEnrollment() {
        if (voiceEnrollmentLaunched) return
        if (voiceStartupRequested) {
            startService(Intent(this, VoiceAssistantService::class.java).setAction(VoiceAssistantService.ACTION_STOP))
            voiceStartupRequested = false
        }
        voiceEnrollmentLaunched = true
        startActivity(Intent(this, VoiceEnrollmentActivity::class.java))
    }

    private fun startVoiceService() {
        if (!VoiceIdentity.hasEnrollment(this)) {
            startVoiceEnrollment()
            return
        }
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
            say("The local listening service has started. After the speech model loads, say “Hey Erez” to activate a command. Stop listening from the notification.")
        } catch (_: Exception) {
            voiceStartupRequested = false
            say("Could not start background listening. Check microphone and notification permissions.")
        }
    }

    private fun searchWeb(query: String) {
        say("Searching the web without an API key…")
        Thread {
            try {
                val results = FreeWebSearch.search(query)
                if (results.isEmpty()) {
                    runOnUiThread { speak("No search results were returned.") }
                    return@Thread
                }
                val sourceText = results.mapIndexed { index, result ->
                    "${index + 1}. ${result.title}\n${result.snippet}\nLink: ${result.url}"
                }.joinToString("\n\n")
                if (!ErezAiBrain.isReady()) {
                    runOnUiThread {
                        log.append("\\n" + sourceText)
                        speak("Found ${results.size} results. Load the local AI engine for a summary.")
                    }
                } else {
                    val answer = StringBuilder()
                    ErezAiBrain.ask(
                        "Answer the question in English: " + query +
                            "\\nUse only the information below. State uncertainty and include the supplied links unchanged.\\n\\n" + sourceText,
                        { part -> answer.append(part) },
                        {
                            runOnUiThread {
                                log.append("\\n" + answer.toString())
                                log.append("\\nSources:\\n" + results.joinToString("\\n") { it.title + " — " + it.url })
                                speak(answer.toString().take(900))
                            }
                        },
                        { error -> runOnUiThread { speak(error) } }
                    )
                }
            } catch (e: Exception) {
                runOnUiThread { speak("Search failed: ${e.message ?: "network error"}") }
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
        say("You: $t")
        when {
            t.contains("what do you see") || t.contains("describe the screen") || t.contains("read the screen") || t.contains("read this") -> {
                if (latestScreenText.isBlank() && latestScreenImagePath.isBlank()) {
                    requestScreenCaptureConsent()
                } else if (!ErezAiBrain.isReady()) {
                    speak("The local AI engine is not ready yet. Complete the initial download.")
                } else {
                    val snapshot = latestScreenImagePath.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
                    val answer = StringBuilder()
                    val prompt = "Describe what is visible on the screen and what is happening. Use the screenshot itself and the recognized text.\n" + latestScreenText.take(5000)
                    val onChunk: (String) -> Unit = { part -> answer.append(part) }
                    val onDone: () -> Unit = { runOnUiThread { speak(answer.toString().ifBlank { "Analysis complete." }) } }
                    val onError: (String) -> Unit = { error -> runOnUiThread { speak(error) } }
                    // Answer from the newest screenshot immediately. Multi-frame video
                    // analysis is an explicit heavier task, not the default screen response.
                    ErezAiBrain.ask(prompt, onChunk, onDone, onError, snapshot)
                }
            }
            t.contains("comment") || t.contains("reply") -> {
                if (!ErezAiBrain.isReady()) {
                    pendingCommandAfterBrain = t
                    prepareBrain()
                } else {
                    val draft = StringBuilder()
                    ErezAiBrain.ask(
                         "Write a concise, natural English comment for the visible post or video. Return only the comment draft. User request: " +
                            t + "\\nScreen content:\\n" + latestScreenText.take(5000),
                        { part -> draft.append(part) },
                        { runOnUiThread { confirmAction(ActionConfirmationActivity.TYPE_COMMENT, "Comment draft", draft.toString()) } },
                        { error -> runOnUiThread { speak(error) } }
                    )
                }
            }
            t.contains("WhatsApp", ignoreCase = true) -> {
                speak("WhatsApp voice-note replies are not connected yet. I will not send a typed message instead.")
            }
            t.contains("email", ignoreCase = true) -> {
                val recipient = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").find(t)?.value.orEmpty()
                val subject = t.substringAfter("subject", "Message from Erez AI").substringBefore("body").trim().ifBlank { "Message from Erez AI" }
                if (ErezAiBrain.isReady()) {
                    val draft = StringBuilder()
                    ErezAiBrain.ask(
                        "Write the email body in English based on the user request. Return only the body, without headings. Request: " + t,
                        { part -> draft.append(part) },
                        { runOnUiThread { confirmAction(ActionConfirmationActivity.TYPE_EMAIL, subject, draft.toString(), recipient = recipient) } },
                        { error -> runOnUiThread { speak(error) } }
                    )
                } else {
                    val body = t.substringAfter("body", t).trim()
                    confirmAction(ActionConfirmationActivity.TYPE_EMAIL, subject, body, recipient = recipient)
                }
            }
            t.contains("call") || t.contains("dial") -> {
                val number = Regex("\\+?[0-9][0-9\\- ]{6,}").find(t)?.value.orEmpty()
                confirmAction(
                    ActionConfirmationActivity.TYPE_CALL,
                    if (number.isBlank()) "Choose a contact to call" else "Calling $number",
                    "",
                    phone = number
                )
            }
            t.contains("reminder") || t.contains("calendar") -> {
                val title = t.substringAfter("reminder", t.substringAfter("calendar", t)).trim()
                confirmAction(ActionConfirmationActivity.TYPE_CALENDAR, title.ifBlank { "New reminder" }, "")
            }
            t.contains("search") || t.contains("Google") || t.contains("web") -> {
                searchWeb(t.removePrefix("search").removePrefix(" the web").removeSuffix(" on the web").trim())
            }
            else -> {
                if (ErezAiBrain.isReady()) {
                    val answer = StringBuilder()
                    val previousLog = log.text.toString()
                    speak("Got it. Let me think…")
                    ErezAiBrain.ask(
                        t,
                        { chunk ->
                            answer.append(chunk)
                            val partial = answer.toString()
                            runOnUiThread { log.text = previousLog + "\nErez: " + partial }
                        },
                        {
                            runOnUiThread {
                                val reply = answer.toString().trim()
                                speak(reply.ifBlank { "The model finished without returning an answer. Please try again." })
                            }
                        },
                        { error -> runOnUiThread { speak("I ran into a problem creating the answer: $error") } }
                    )
                } else {
                    pendingCommandAfterBrain = t
                    prepareBrain()
                }
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}