package com.tanveer.aiva.data.local

const val DEMO_FOOTER = "*Demo response · no AI service is connected.*"

/** Offline replies used only by the AIVA Demo model. Always labelled. */
fun demoReply(prompt: String): String {
    val p = prompt.lowercase()
    val body = when {
        Regex("code|kotlin|function|android").containsMatchIn(p) ->
            "Here’s a tidy starting point:\n\n```kotlin\nfun main() {\n    val ideas = listOf(\"calm\", \"curious\", \"kind\")\n    ideas.forEach { println(\"Stay \$it\") }\n}\n```\n\nSwap in your own logic and keep functions small."
        Regex("trip|travel|weekend|visit").containsMatchIn(p) ->
            "A gentle plan:\n\n- **Morning:** a slow breakfast somewhere local.\n- **Afternoon:** one main sight, nothing rushed.\n- **Evening:** a walk and an early night."
        Regex("name|idea|brand").containsMatchIn(p) ->
            "A few directions to explore: **Soft Studio**, **Northlight**, and **Kind Signal**. Which feeling fits best?"
        else ->
            "That’s a lovely question. With a connected model, the real answer would appear here. For now this is a sample reply so you can try the conversation flow."
    }
    return "$body\n\n$DEMO_FOOTER"
}

/** Text suitable for speech: no demo label, no markdown fences. */
fun plainSpeech(text: String): String {
    val noFences = text.replace(Regex("```[\\s\\S]*?```"), " ")
    return noFences
        .replace(DEMO_FOOTER, " ")
        .replace(Regex("[*_#>`\\[\\]]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(3500)
}
