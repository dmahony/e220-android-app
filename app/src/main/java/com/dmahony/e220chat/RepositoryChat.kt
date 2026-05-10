package com.dmahony.e220chat

import java.util.concurrent.ThreadLocalRandom

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

internal suspend fun E220Repository.sendMessage(message: String, messageId: Long = ThreadLocalRandom.current().nextLong()): String {
    if (useBinaryTransport) {
        val sourceUserId = binaryConfig?.userId24
            ?: E220ConfigMapper.defaultBinaryConfig(selectedDeviceAddress).userId24
        val messageIdHex = messageId.toULong().toString(16).padStart(16, '0')
        markBinaryOutgoingMessagePending(
            messageId = messageIdHex,
            text = message,
            senderUserId24 = sourceUserId,
            senderName = binaryConfig?.username.orEmpty()
        )
        bleV2.sendText(sourceUserId, messageId, message)
        markBinaryOutgoingMessageStatus(messageIdHex, DeliveryStatus.SENT)
        appendTransportLog(TransportDirection.SENT, "TEXT src=${sourceUserId.toString(16).padStart(6, '0')} msg=$messageIdHex len=${message.length}")
        return "queued"
    }
    val response = exchange(E220Protocol.buildSendRequest(message))
    return E220Protocol.parseSendAcknowledgement(response)
}
