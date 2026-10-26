# AIVA Implementation Manifest

## Completed in this edit pass

- Preserved the new AIVA Material 3/XML UI structure and existing navigation.
- Connected Android speech recognition to AIVA chat generation/history and wired completed replies to Android TextToSpeech.
- Added voice lifecycle handling so leaving the voice page stops capture and speech playback.
- Connected Gemini text chat to SSE streaming with bounded pre-output retries.
- Added safe OpenRouter model fallback for eligible transient/compatibility errors when no partial output has been emitted; image conversations only fall back to image-capable models.
- Added Google-first, Hugging Face-second image generation routing when both credentials are not necessarily present.
- Updated model readiness and Settings behavior for image fallback and actual device-provided TTS voices.
- Added generated-image Save through Android's document picker and corrected FileProvider authority/MIME handling for sharing.
- Guarded simultaneous chat submissions and made generation completion callbacks request-local.
- Tightened API-key shape validation and corrected image-generation missing-key feedback.
- Removed the incomplete duplicate `:protocol` module from the active Gradle build path.
- Ran static XML, resource-ID and Kotlin-parser-shaped checks.

## Not completed / not verified

- The actual legacy AVIA source ZIP was not supplied; this is a workflow-based integration from the supplied analysis, not an exact source merge.
- AVIA's stored session/history migration was not implemented without the legacy storage source/schema.
- No Gradle compilation, unit tests, release/R8 build, live provider test, or physical-device UI/voice test could be completed.
- Model/provider endpoint availability and image-editing support remain to be verified using valid API credentials.

## Environment blocker

`./gradlew :app:compileDebugKotlin --no-daemon` could not resolve/download Gradle 8.11.1 because `services.gradle.org` was unreachable (`UnknownHostException`). No successful compile is claimed.

## Recommended next validation

1. Open the edited source in CodeAssist/Android Studio with the Android SDK and Gradle dependencies available.
2. Run a debug compile and fix any compiler or resource-linking errors.
3. Test OpenRouter streaming/fallback, Gemini streaming, vision payloads, HF fallback, voice/STT/TTS, image Save/Share, and history persistence with valid credentials.
4. Test cancellation, no network, invalid keys, HTTP 429/5xx, missing image files, permission denial, activity navigation, and process death.
5. Run a minified release build and inspect the resulting APK before distribution.
