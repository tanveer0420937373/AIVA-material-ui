package com.tanveer.aiva.data.local

import com.tanveer.aiva.domain.models.ChatMessage
import com.tanveer.aiva.domain.models.ChatThread
import com.tanveer.aiva.domain.models.Delivery
import com.tanveer.aiva.domain.models.Role
import org.json.JSONArray
import org.json.JSONObject

/**
 * Conversation file codec.
 *
 * Schema 2 is a JSON object: `{"schema":2,"threads":[...]}`.
 * Schema 1 (and any raw JSON array written by older builds) is still read.
 * Files with a higher schema are rejected so the caller can keep a recovery copy.
 */
object ThreadCodec {
    const val SCHEMA = 2

    fun encode(threads: List<ChatThread>): String {
        val root = JSONObject()
            .put("schema", SCHEMA)
            .put("threads", JSONArray().also { arr -> threads.forEach { arr.put(writeThread(it)) } })
        return root.toString()
    }

    fun decode(json: String): List<ChatThread> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("[")) return decodeArray(JSONArray(trimmed))
        val root = JSONObject(trimmed)
        val schema = root.optInt("schema", 1)
        if (schema > SCHEMA) {
            throw IllegalArgumentException("History schema $schema is newer than this app.")
        }
        return decodeArray(root.optJSONArray("threads") ?: JSONArray())
    }

    private fun decodeArray(arr: JSONArray): List<ChatThread> =
        (0 until arr.length()).map { readThread(arr.getJSONObject(it)) }

    private fun writeThread(t: ChatThread) = JSONObject()
        .put("id", t.id)
        .put("title", t.title)
        .put("updatedAt", t.updatedAt)
        .put("messages", JSONArray().also { a -> t.messages.forEach { a.put(writeMessage(it)) } })

    private fun writeMessage(m: ChatMessage) = JSONObject()
        .put("id", m.id)
        .put("role", m.role.name)
        .put("text", m.text)
        .put("createdAt", m.createdAt)
        .put("isError", m.isError)
        .put("wasStopped", m.wasStopped)
        .put("delivery", m.delivery.name)
        .put("attachments", JSONArray(m.attachments))
        .put("generatedImages", JSONArray(m.generatedImages))

    private fun readThread(o: JSONObject): ChatThread {
        val msgs = o.optJSONArray("messages") ?: JSONArray()
        return ChatThread(
            id = o.getString("id"),
            title = o.optString("title").ifBlank { "Conversation" },
            updatedAt = o.optLong("updatedAt"),
            messages = (0 until msgs.length()).mapNotNull { index ->
                runCatching { readMessage(msgs.getJSONObject(index)) }.getOrNull()
            },
        )
    }

    private fun readMessage(o: JSONObject): ChatMessage {
        val delivery = o.optString("delivery").takeIf { it.isNotBlank() }?.let {
            runCatching { Delivery.valueOf(it) }.getOrNull()
        } ?: legacyDelivery(o.optBoolean("isError"), o.optBoolean("wasStopped"))
        return ChatMessage(
            id = o.getString("id"),
            role = Role.valueOf(o.getString("role")),
            text = o.optString("text"),
            createdAt = o.optLong("createdAt"),
            isError = delivery == Delivery.Failed || o.optBoolean("isError"),
            wasStopped = delivery == Delivery.Stopped || delivery == Delivery.Partial || o.optBoolean("wasStopped"),
            attachments = stringList(o.optJSONArray("attachments")),
            generatedImages = stringList(o.optJSONArray("generatedImages")),
            delivery = delivery,
        )
    }

    private fun legacyDelivery(isError: Boolean, wasStopped: Boolean): Delivery = when {
        isError -> Delivery.Failed
        wasStopped -> Delivery.Stopped
        else -> Delivery.Complete
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { index ->
            arr.optString(index).takeIf { it.isNotBlank() }
        }
    }
}
