# AIVA Live Voice orb: integration notes

## Pieces
- `assets/AIVA_Orb_Android_Ready.html`: the orb (WebGL, Canvas2D fallback). Transparent, no network, no mic.
- `voice/orb/OrbController.kt`: the only Kotlin class that talks to the page. Owns the WebView.
- `voice/session/VoiceSession.kt`: the existing recognizer + TTS pipeline (unchanged architecture).
- `MainActivity.setupVoice`: creates one controller per Voice screen, feeds it `session.state` and `session.inputLevel`.
- `res/layout/screen_voice.xml`: `voice_orb_container` holds the WebView; `voice_orb` (static JPG) is the fallback.

## Kotlin -> page API (window.AIVAOrbApp)
| Kotlin | Page call |
|---|---|
| readiness probe (polled after page load) | `isReady()` + `getStatus().renderer` (`webgl`, `webgl-low`, `canvas2d`, `none`) |
| `setState(OrbState)` | `setState('idle'\|'listening'\|'processing'\|'speaking'\|'muted'\|'error')` |
| `setAmplitude(Float)` | `setAmplitude(0.000..1.000)`, listening only, max 20/s |
| on ready | `setOptions({gate:0.10})` |
| `pause()` / `resume()` | `pause()` / `resume()` plus `WebView.onPause/onResume` (Activity stop/start) |
| `reset()` | `reset()` |

`VoiceState` -> `OrbState` is a 1:1 exhaustive `when` in `OrbState.from`.

## Security
- Page is served by `shouldInterceptRequest` from the bundled asset at a virtual https URL; every other request gets a 404 and all navigation is refused.
- `allowFileAccess`, `allowContentAccess`, file-URL access, DOM storage, zoom and multi-window are off. No `addJavascriptInterface`. No `WebChromeClient`, so the page cannot get camera/mic/geolocation.
- Only fixed strings and a formatted number are ever evaluated in the page.

## Failure behaviour
Load error, HTTP error, missing asset, no renderer, 10 s timeout, or repeated renderer crash -> WebView is destroyed, the static orb stays visible, `OrbStatus.Failed` is reported (one toast per app session). One automatic WebView restart after a renderer crash.

## Tuning knobs (need a real device)
- `INPUT_NOISE_GATE` in `OrbController` (page-side gate on top of `inputLevelFromRms`).
- `AMPLITUDE_MIN_INTERVAL_MS` (50 ms).
- Speaking uses the page's built-in animation: Android TTS exposes no amplitude.
