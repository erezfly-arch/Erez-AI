# Erez AI — English Core (Android)

This Android-first Kotlin assistant is being migrated to an English-first experience.

## Product requirements
- Wake phrase: Hey Erez.
- English speech recognition, reasoning instructions, system status messages, and assistant voice output.
- Read and analyze the phone screen only after the user grants Android MediaProjection consent; optional Accessibility access reads visible UI text.
- Preserve WhatsApp voice replies as the only WhatsApp reply mode. Compose a spoken reply, let the user review/record it, and send only with explicit confirmation. Do not silently send messages.
- Keep the local/offline model architecture and avoid paid API keys, subscriptions, or per-request fees.

## Current implementation baseline
The app uses a local Qwen2-VL 2B model through LiteRT-LM for text and image understanding, MediaProjection for screen sampling, sherpa-onnx Whisper Base multilingual for speech recognition, Android TextToSpeech for spoken output, and an optional AccessibilityService for visible UI text.

## Important Android constraints
- A reliable always-on wake phrase requires a foreground microphone service and a persistent Android notification; Android may restrict microphone access in the background. The user must explicitly start and grant permissions.
- Screen capture requires Android system consent and cannot be silently enabled by the app.
- WhatsApp does not expose a universal public API for sending arbitrary voice notes. Voice-note sending must use supported Android UI/accessibility interaction where available, and remain user-confirmed. The app must not claim it can send a voice note until validated on the target device and WhatsApp version.
- Each phone action needs a real Android implementation, permission, and safety/confirmation handling.

## Build
GitHub Actions builds the debug APK on pushes to main. This migration is on branch english-core-hey-erez until the build and device tests pass.
