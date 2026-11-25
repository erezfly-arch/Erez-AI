# ארז AI — Android first

Private Android-first personal assistant project. iOS work is intentionally deferred until the Android version is complete.

## Current engineering baseline
- Native Kotlin Android app (minSdk 26, target SDK 35).
- Hebrew command entry and speech recognition intent.
- Explicit Android MediaProjection consent flow and foreground notification for screen sharing.
- Accessibility-service foundation, disabled until the user enables it in Android settings.
- Intents for WhatsApp share, email composer, calendar event creation, phone dialer, and web search. These open the relevant app and leave final sending/calling/creation confirmation to the user.
- GitHub Actions builds a debug APK artifact on push or manual dispatch.

## Not complete yet
This repository is an engineering foundation, **not a finished assistant**. Screen frames are not yet processed or described; OCR is not wired; no conversational AI model is integrated; no persistent floating voice interface is implemented; accessibility actions are not yet connected to a safe command planner; WhatsApp/email sending and calls are not silently automated. No iOS implementation exists.

## Safety and platform constraints
Screen capture and accessibility require explicit user consent. Android may block capture of protected content. Sending messages, publishing comments, creating calendar entries, and placing calls should show a review/confirmation step. The app must keep a visible indicator while screen access is active and provide an immediate stop control.

## Build
GitHub Actions workflow: `.github/workflows/build-apk.yml`. It builds `assembleDebug` and publishes the APK as the `Erez-AI-debug-APK` artifact.

## Cost target
Prefer local/open-source components and free integrations. This cannot guarantee that every cloud-grade AI capability or web service will remain free forever; avoid paid APIs and subscription dependencies by default.
