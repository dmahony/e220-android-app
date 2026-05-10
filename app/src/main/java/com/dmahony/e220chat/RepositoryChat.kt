package com.dmahony.e220chat

internal suspend fun E220Repository.getChat(sinceSequence: Int = 0): ChatSnapshot {
    if (useBinaryTransport) {
        val reset = binaryChatReset
        binaryChatReset = false
        val outMessages = synchronized(binaryChatMessages) {
            if (sinceSequence <= 0 || reset) {
                binaryChatMessages.toList()
            } else if (sinceSequence < binaryChatSequence) {
                val from = sinceSequence.coerceAtLeast(0)
                if (from >= binaryChatMessages.size) emptyList() else binaryChatMessages.subList(from, binaryChatMessages.size).toList()
            } else {
                emptyList()
            }
        }
        return ChatSnapshot(sequence = binaryChatSequence, messages = outMessages, reset = reset)
    }
    return E220Protocol.parseChatResponse(exchange(E220Protocol.buildChatRequest(sinceSequence)))
}

internal suspend fun E220Repository.clearChatHistory() {
    if (useBinaryTransport) {
        synchronized(binaryChatMessages) {
            binaryChatMessages.clear()
            binaryChatReset = true
            binaryChatSequence = 0
        }
        return
    }
    exchange(E220Protocol.buildClearChatRequest())
}

internal suspend fun E220Repository.sendMessage(message: String): String {
    if (useBinaryTransport) {
        val destination = parseDestinationUserId()
        val messageId = java.util.concurrent.ThreadLocalRandom.current().nextLong()
        val senderUserId = binaryConfig?.userId24 ?: destination
        bleV2.sendText(destination, messageId, message)
        synchronized(binaryChatMessages) {
            binaryChatMessages.add(
                ChatMessage(
                    text = message,
                    sent = true,
                    delivered = false,
                    senderUserId24 = senderUserId,
                    messageId = messageId.toULong().toString(16).padStart(16, '0'),
                    deliveryStatus = DeliveryStatus.SENT
                )
            )
            binaryChatSequence += 1
            binaryChatReset = true
        }
        appendTransportLog(TransportDirection.SENT, "TEXT dst=${destination.toString(16).padStart(6, '0')} msg=${messageId.toULong().toString(16).padStart(16, '0')} len=${message.length}")
        return "queued"
    }
    val response = exchange(E220Protocol.buildSendRequest(message))
    return E220Protocol.parseSendAcknowledgement(response)
}
