# AIVA — Native Kotlin + XML UI

This is the new AIVA target project. The existing Material 3 presentation is implemented with native Android XML layouts and Kotlin view/controller code. The package/application ID remains `com.tanveer.aiva`.

## UI structure retained

- `res/layout/activity_main.xml` provides the activity host and bottom navigation.
- `screen_home.xml`, `screen_chat.xml`, `screen_history.xml`, `screen_models.xml`, `screen_settings.xml`, and `screen_voice.xml` define the main screens.
- `item_thread.xml`, `item_model.xml`, and `item_message.xml` define repeatable rows. The assistant message row now includes a Save action for generated images.
- `MainActivity.kt` manages navigation, input, attachments, provider-key controls, the connected voice session, and image save/share.
- Provider networking and chat-generation logic remain in repositories/provider classes rather than moving into XML.

## AVIA workflow integration status

Workflow changes were made against the supplied `AVIA_App_Workflow_Analysis.md`. The old AVIA source ZIP was not included, so exact class-by-class parity or history import is not claimed. See `AUDIT_REPORT.md` for the change list, validation status, and limitations.

Integrated paths include OpenRouter streaming/fallback, Gemini SSE chat streaming, Hugging Face chat/image request routing, Google-first/Hugging-Face-second image generation, Android speech recognition, TTS response playback, image Save/Share, and single-request concurrency protection.

## Build

Open the project root (the directory containing `settings.gradle.kts`) in Android Studio or CodeAssist and sync Gradle. The project targets SDK 35, Java/Kotlin 17, and min SDK 26 by default. A debug/release build was **not verified** in this environment because the Gradle wrapper distribution could not be downloaded; the Android SDK/dependencies were also unavailable to the local compiler check.

## Security and testing

API keys use the existing Android Keystore-backed storage and are not pre-filled in Settings. Never add real keys to source files or repositories. Voice recognition depends on the device's recognition service and microphone permission; no always-listening assistant is implemented. Before distribution, run a successful debug and minified release build, then validate provider calls and all high-priority workflows on a device.
