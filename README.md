# Erez AI — English-first Android assistant

## Target behavior
- Wake phrase: **Hey Erez**. A first-run 9-second voice enrollment creates a local speaker profile; the wake phrase is checked against it before the assistant responds.
- English-first commands, reasoning, status messages, and assistant replies.
- Multilingual speech and screen understanding: Hebrew and English source text must be preserved; reading Hebrew aloud uses a Hebrew Android TTS voice when installed.
- Screen projection is requested only when the user asks for screen/image understanding. Accessibility access is optional and requires the user to enable the Android service.
- WhatsApp replies are intended to be voice notes only. Typed WhatsApp sending has been removed from the current command flow.
- Local/offline inference avoids paid API keys and per-request API fees. Device storage, RAM, CPU speed, battery, model licenses, and Android permission rules still apply.

## Current implementation
The app uses a local Qwen2-VL 2B model through LiteRT-LM, MediaProjection for user-approved screen capture, sherpa-onnx Whisper Base multilingual for offline speech recognition, a local sherpa-onnx speaker-embedding model for wake-word speaker checks, Android TextToSpeech with an offline Hebrew voice fallback, and an optional AccessibilityService for visible UI text.

## Important gaps — not yet release-ready
- Wake-word standby currently uses offline Whisper transcription to detect the phrase. It is not yet a dedicated low-power keyword-spotting engine, so microphone foreground service and CPU/battery use must be measured on the target phone.
- Speaker verification is probabilistic, not secure biometric authentication. It can reject the owner in noisy conditions or accept a sufficiently similar voice; never use it as the sole protection for payments, account access, or other high-risk actions.
- WhatsApp voice-note recording and sending are not implemented yet. The app must not claim to send voice notes until the flow is built and tested against the installed WhatsApp version.
- General phone actions are not universally supported. Each action needs a concrete Android implementation, the relevant permission, and a confirmation step where appropriate.
- A successful CI build does not prove runtime reliability on the target Xiaomi/Android device. Real-device tests are still required before release.

## Android constraints
- A reliable background wake phrase requires a foreground microphone service and a persistent Android notification. Android and device battery-management settings may restrict background microphone access.
- Screen capture requires Android system consent and cannot be silently enabled by the app.
- Voice matching is probabilistic and must not be treated as secure biometric authentication for payments, account access, or other high-risk actions.

## Build
GitHub Actions builds a debug APK on pushes to `main` and `english-core-hey-erez`, and on pull requests to `main`. The migration stays on its development branch until the APK builds and device tests pass.
