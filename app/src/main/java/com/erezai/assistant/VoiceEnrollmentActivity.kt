package com.erezai.assistant

import android.app.Activity
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity

class VoiceEnrollmentActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var recordButton: Button
    @Volatile private var working = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 36, 28, 36)
            setBackgroundColor(0xFF101827.toInt())
        }
        root.addView(TextView(this).apply {
            text = "Set up your voice profile"
            textSize = 25f
            setTextColor(-1)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Record one short sample so Erez can check the speaker before responding to “Hey Erez”. Speak naturally in English for about 9 seconds. Include the words “Hey Erez” and a few full sentences. The sample stays on this device; only the derived voice profile is saved."
            textSize = 16f
            setTextColor(0xFFE0E7F0.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 22, 0, 24)
        })
        status = TextView(this).apply {
            text = "The speaker model is downloaded once and runs locally."
            textSize = 14f
            setTextColor(0xFFB7C4D8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        root.addView(status)
        recordButton = Button(this).apply {
            text = "Record my voice"
            setOnClickListener { beginEnrollment() }
        }
        root.addView(recordButton)
        root.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        })
        setContentView(root)
    }

    private fun beginEnrollment() {
        if (working) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            status.text = "Microphone permission is required. Return to Erez AI and grant it first."
            return
        }
        working = true
        recordButton.isEnabled = false
        Thread {
            val identity = VoiceIdentity(applicationContext)
            try {
                identity.initialize { message -> runOnUiThread { status.text = message } }
                runOnUiThread { status.text = "Recording now. Speak clearly until recording finishes…" }
                val recording = recordNineSeconds()
                val chunkSize = SAMPLE_RATE * 3
                val chunks = (0 until 3).map { index ->
                    recording.copyOfRange(index * chunkSize, (index + 1) * chunkSize)
                }
                runOnUiThread { status.text = "Learning your voice locally…" }
                identity.enroll(chunks)
                runOnUiThread {
                    status.text = "Voice profile saved on this device."
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Voice setup failed: ${e.message ?: "unknown error"}. Please try again."
                    recordButton.isEnabled = true
                    working = false
                }
            } finally {
                identity.close()
            }
        }.start()
    }

    private fun recordNineSeconds(): FloatArray {
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimum <= 0) throw IllegalStateException("The microphone does not provide a supported audio buffer.")
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum * 2, SAMPLE_RATE * 2)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("Could not initialize the microphone.")
        }
        val output = FloatArray(SAMPLE_RATE * 9)
        val buffer = ShortArray(1600)
        var offset = 0
        try {
            recorder.startRecording()
            while (offset < output.size) {
                val count = recorder.read(buffer, 0, minOf(buffer.size, output.size - offset))
                if (count < 0) throw IllegalStateException("Microphone recording failed: $count")
                for (i in 0 until count) output[offset + i] = buffer[i] / 32768.0f
                offset += count
            }
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
        }
        var energy = 0.0
        for (sample in output) energy += sample * sample
        val rms = kotlin.math.sqrt(energy / output.size).toFloat()
        if (rms < 0.0025f) throw IllegalStateException("The recording was too quiet. Speak closer to the phone microphone.")
        return output
    }

    companion object {
        private const val SAMPLE_RATE = 16000
    }
}
