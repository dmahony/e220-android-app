package com.dmahony.e220chat

import com.dmahony.e220chat.ble.FlowState

internal suspend fun E220Repository.getDiagnostics(): Diagnostics {
    if (useBinaryTransport) {
        val st = binaryStatus
        return Diagnostics(
            e220Timeouts = 0,
            e220RxErrors = 0,
            e220TxErrors = if (st?.flowState == FlowState.TX_FAILED) 1 else 0,
            uptimeMs = (st?.uptimeSec ?: 0L) * 1000L,
            freeHeap = 0,
            minFreeHeap = 0,
            btName = selectedDeviceName.orEmpty(),
            btHasClient = isConnected,
            btRequestCount = binaryChatSequence,
            btParseErrors = 0,
            btRawMessageCount = binaryChatSequence,
            lastRssi = st?.lastRssi ?: 0
        )
    }
    return E220Protocol.parseDiagnosticsResponse(exchange(E220Protocol.buildDiagnosticsRequest()))
}

internal suspend fun E220Repository.getDebug(): String {
    if (useBinaryTransport) {
        val st = binaryStatus
        return "BLE v2 connected=$isConnected flow=${st?.flowState} batteryMv=${st?.batteryMv} rssi=${st?.lastRssi} qBleRx=${st?.qBleRx} qBleTx=${st?.qBleTx} qRadioTx=${st?.qRadioTx} qRadioRx=${st?.qRadioRx}"
    }
    return E220Protocol.parseDebugLog(exchange(E220Protocol.buildDebugRequest()))
}

internal suspend fun E220Repository.clearDebug() {
    if (useBinaryTransport) return
    exchange(E220Protocol.buildDebugClearRequest())
}
