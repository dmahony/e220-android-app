package com.dmahony.e220chat

import com.dmahony.e220chat.ble.FlowState

internal suspend fun E220Repository.getOperation(): OperationStatus {
    if (useBinaryTransport) {
        val st = binaryStatus
        val msg = when (st?.flowState) {
            FlowState.BUSY -> "busy"
            FlowState.TX_IN_PROGRESS -> "tx_in_progress"
            FlowState.TX_DONE -> "tx_done"
            FlowState.TX_FAILED -> "tx_failed"
            else -> "ready"
        }
        return OperationStatus(type = "ble_v2", state = "idle", message = msg, updatedAtMs = System.currentTimeMillis(), rawResult = "{}")
    }
    return E220Protocol.parseOperationResponse(exchange(E220Protocol.buildOperationRequest()))
}
