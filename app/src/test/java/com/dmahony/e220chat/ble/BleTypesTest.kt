package com.dmahony.e220chat.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BleTypesTest {
    @Test(expected = IllegalArgumentException::class)
    fun `config payload rejects too short input`() {
        BleConfig.fromPayload(byteArrayOf(0x00, 0x01, 0x02))
    }

    @Test
    fun `config payload matches firmware schema`() {
        val cfg = BleConfig(
            ackTimeoutMs = 200,
            maxRetries = 5,
            radioTxIntervalMs = 300,
            statusIntervalMs = 1200,
            profileIntervalSec = 42,
            userId24 = 0x123456,
            username = "node-A",
            channel = 37,
            txpower = 30,
            baud = 3,
            parity = 1,
            airrate = 2,
            txmode = 1,
            lbt = 1,
            subpkt = 2,
            rssiNoise = 1,
            rssiByte = 1,
            urxt = 6,
            worCycle = 4,
            cryptH = 0xAA,
            cryptL = 0x55,
            saveType = 1,
            addr = 0xBEEF,
            dest = 0xCAFE,
            wifiEnabled = 1,
            wifiMode = 2,
            wifiApSsid = "AP-SSID",
            wifiApPassword = "ap-pass",
            wifiStaSsid = "STA-SSID",
            wifiStaPassword = "sta-pass"
        )

        val payload = cfg.toPayload()

        assertEquals(33 + 1 + cfg.username.length + 1 + cfg.wifiApSsid.length + 1 + cfg.wifiApPassword.length + 1 + cfg.wifiStaSsid.length + 1 + cfg.wifiStaPassword.length, payload.size)
        assertEquals(0x00.toByte(), payload[0])
        assertEquals(0xC8.toByte(), payload[1])
        assertEquals(5.toByte(), payload[2])
        assertEquals(0x01.toByte(), payload[3])
        assertEquals(0x2C.toByte(), payload[4])
        assertEquals(0x04.toByte(), payload[5])
        assertEquals(0xB0.toByte(), payload[6])
        assertEquals(0x00.toByte(), payload[7])
        assertEquals(0x2A.toByte(), payload[8])
        assertEquals(0x12.toByte(), payload[9])
        assertEquals(0x34.toByte(), payload[10])
        assertEquals(0x56.toByte(), payload[11])
        assertEquals(37.toByte(), payload[12])
        assertEquals(30.toByte(), payload[13])
        assertEquals(3.toByte(), payload[14])
        assertEquals(1.toByte(), payload[15])
        assertEquals(2.toByte(), payload[16])
        assertEquals(1.toByte(), payload[17])
        assertEquals(1.toByte(), payload[18])
        assertEquals(2.toByte(), payload[19])
        assertEquals(1.toByte(), payload[20])
        assertEquals(1.toByte(), payload[21])
        assertEquals(6.toByte(), payload[22])
        assertEquals(4.toByte(), payload[23])
        assertEquals(0xAA.toByte(), payload[24])
        assertEquals(0x55.toByte(), payload[25])
        assertEquals(1.toByte(), payload[26])
        assertEquals(0xBE.toByte(), payload[27])
        assertEquals(0xEF.toByte(), payload[28])
        assertEquals(0xCA.toByte(), payload[29])
        assertEquals(0xFE.toByte(), payload[30])
        assertEquals(1.toByte(), payload[31])
        assertEquals(2.toByte(), payload[32])
    }

    @Test
    fun `status payload round trips through firmware schema`() {
        val payload = byteArrayOf(
            0x03,
            0x0A, 0x0B,
            0xB8.toByte(),
            0x01,
            0x02,
            0x03,
            0x04,
            0x01,
            0x02,
            0x03,
            0x04,
            0x01,
            0x02,
            0x03,
            0xAA.toByte(),
            0xBB.toByte(),
            0xCC.toByte()
        )

        val status = StatusTelemetry.fromPayload(payload)

        assertEquals(FlowState.TX_DONE, status.flowState)
        assertEquals(0x0A0B, status.batteryMv)
        assertEquals(-72, status.lastRssi)
        assertEquals(1, status.qBleRx)
        assertEquals(2, status.qRadioTx)
        assertEquals(3, status.qRadioRx)
        assertEquals(4, status.qBleTx)
        assertEquals(0x01020304L, status.uptimeSec)
        assertEquals(1, status.fwMajor)
        assertEquals(2, status.fwMinor)
        assertEquals(3, status.fwPatch)
        assertEquals(0xAABBCC, status.deviceId24)
    }
}
