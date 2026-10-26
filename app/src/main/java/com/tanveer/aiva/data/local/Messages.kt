package com.tanveer.aiva.data.local

import com.tanveer.aiva.domain.models.ChatMessage
import com.tanveer.aiva.domain.models.Delivery
import com.tanveer.aiva.domain.models.Role
import java.util.UUID

fun newMessage(role: Role, text: String, attachments: List<String> = emptyList()) = ChatMessage(
    id = UUID.randomUUID().toString(),
    role = role,
    text = text,
    createdAt = System.currentTimeMillis(),
    attachments = attachments,
    delivery = Delivery.Complete,
)

fun ChatMessage.withDelivery(delivery: Delivery, text: String = this.text, images: List<String> = generatedImages) = copy(
    text = text,
    generatedImages = images,
    delivery = delivery,
    isError = delivery == Delivery.Failed,
    wasStopped = delivery == Delivery.Stopped || delivery == Delivery.Partial,
)
