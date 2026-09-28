package com.wiglywoo.ecosystem

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.wiglywoo.CompanionConfig
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Advertises an 8-byte token derived from the pairing secret, re-derived
 * every minute. The Mac decides near/far from RSSI; there is no UWB on this
 * pair, so distance stays coarse. The token rides in manufacturer data
 * (company 0xFFFF, reserved for testing): a 128-bit service UUID plus data
 * does not fit the 31-byte legacy advertisement.
 */
object BlePresence {
    const val COMPANY_ID = 0xFFFF

    /** Set by the Mac's presence reports. Unknown counts as near. */
    @Volatile var near: Boolean = true

    private val main = Handler(Looper.getMainLooper())
    private var advertiser: BluetoothLeAdvertiser? = null
    private var context: Context? = null
    private val callback = object : AdvertiseCallback() {}
    private val rotate = object : Runnable {
        override fun run() {
            advertise()
            main.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000)
        }
    }

    fun token(secret: String, minute: Long = System.currentTimeMillis() / 60_000): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal("wigly-ble-v1|$minute".toByteArray()).copyOf(8)
    }

    fun update(context: Context, on: Boolean) {
        main.removeCallbacks(rotate)
        stopAdvertising()
        this.context = context.applicationContext
        if (on && CompanionConfig.load(context).isComplete) main.post(rotate)
    }

    @SuppressLint("MissingPermission") // update(true) is only called with BLUETOOTH_ADVERTISE granted
    private fun advertise() {
        val ctx = context ?: return
        stopAdvertising()
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val le = adapter.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .addManufacturerData(COMPANY_ID, token(CompanionConfig.load(ctx).pairingSecret))
            .setIncludeDeviceName(false)
            .build()
        if (runCatching { le.startAdvertising(settings, data, callback) }.isSuccess) advertiser = le
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        advertiser?.let { runCatching { it.stopAdvertising(callback) } }
        advertiser = null
    }
}
