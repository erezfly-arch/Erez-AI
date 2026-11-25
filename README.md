# ארז AI — Android first

Private Android-first personal assistant project. iOS work is intentionally deferred until the Android version is complete.

## Implemented engineering components
- Native Kotlin Android app (minSdk 26, target SDK 35).
- Hebrew command entry, Android speech-recognition intent, and Hebrew text-to-speech output.
- Explicit Android MediaProjection consent flow with an ongoing foreground notification and a stop control.
- On-device ML Kit OCR pipeline for text visible in captured screen frames (Latin-script recognition).
- Optional Android AccessibilityService that can read visible UI text after the user explicitly enables it in Android Accessibility settings. This is the path for UI text that is not recognized by the OCR model, including many Hebrew UI elements.
- Intents to open WhatsApp share, email composer, calendar event creation, phone dialer, and web search. Final sending/calling/creation confirmation remains with the user.
- GitHub Actions workflow builds a debug APK artifact on push or manual dispatch.

## Still required before the app is considered complete
This is **not yet the finished assistant**:
- A persistent floating assistant interface and continuous two-way voice conversation while another app (for example Facebook video) is in the foreground.
- A robust Hebrew-capable visual understanding layer combining accessibility content and image OCR, with safe summaries of videos/images rather than just extracted text.
- A real conversational reasoning model. The current command handling is rule-based and does not understand arbitrary natural language.
- A safe action planner that can fill text fields, draft comments, send WhatsApp/email messages, create calendar reminders, and place calls with review/confirmation steps.
- Web search integration that returns grounded summaries and source links inside the assistant, rather than opening a browser search page.
- Device-level integration tests and a verified release build.

## Safety and Android platform constraints
Screen capture and accessibility require explicit user consent. Android may block capture of protected content. Screen access must have a persistent visible indicator and immediate stop control. Messages, public comments, calendar changes, and calls must be reviewed before execution. Accessibility access is powerful and must remain under the user's control.

## Build
GitHub Actions workflow: `.github/workflows/build-apk.yml`. It builds `assembleDebug` and publishes the APK as the `Erez-AI-debug-APK` artifact.

## Cost target
Prefer local/open-source components and free integrations. No paid API is required by the current foundation. This cannot guarantee that every cloud-grade AI capability or web service will remain free forever; avoid paid APIs and subscription dependencies by default.
