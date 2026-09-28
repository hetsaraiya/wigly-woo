package com.wiglywoo.ecosystem

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import com.wiglywoo.CompanionConfig
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Advertises a token that rotates every minute. The Mac decides near/far from
 * RSSI. There is no UWB on this pair, so distance stays coarse.
 */
object BlePresence {
    val service: UUID = UUID.fromString("8f3c1c0e-6a3a-4b1e-9e2a-7c5d9a1b0001")
    @Volatile var near: Boolean = true

    fun token(secret: String, minute: Long = System.currentTimeMillis() / 60_000): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal("wigly-ble-v1|$minute".toByteArray()).copyOf(8)
    }

    fun start(context: Context) {
        val config = CompanionConfig.load(context)
        if (!config.isComplete) return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(service))
            .addServiceData(ParcelUuid(service), token(config.pairingSecret))
            .setIncludeDeviceName(false)
            .build()
        runCatching { advertiser.startAdvertising(settings, data, object : android.bluetooth.le.AdvertiseCallback() {}) }
    }

    fun isFar(): Boolean = !near
}
