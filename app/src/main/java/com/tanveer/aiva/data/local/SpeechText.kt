package com.tanveer.aiva.data.local

/** Text suitable for on-device speech: no markdown fences or emphasis marks. */
fun plainSpeech(text: String): String {
    val noFences = text.replace(Regex("```[\\s\\S]*?```"), " ")
    return noFences
        .replace(Regex("[*_#>`\\[\\]]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(3500)
}
