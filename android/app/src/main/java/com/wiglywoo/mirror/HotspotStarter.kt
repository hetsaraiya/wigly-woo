package com.wiglywoo.mirror

import android.content.Context
import android.net.TetheringManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RequiresApi(30)
internal object HotspotStarter {
    fun start(ctx: Context): String {
        val tm = ctx.getSystemService(TetheringManager::class.java)
            ?: return JSONObject().put("error", "no tethering service").toString()
        val done = CountDownLatch(1)
        val error = AtomicReference<String>()
        val callback = object : TetheringManager.StartTetheringCallback() {
            override fun onTetheringStarted() { done.countDown() }
            override fun onTetheringFailed(errorCode: Int) {
                error.set("tethering refused ($errorCode)")
                done.countDown()
            }
        }
        tm.startTethering(
            TetheringManager.TETHERING_WIFI,
            false,
            Executors.newSingleThreadExecutor(),
            callback,
        )
        done.await(4, TimeUnit.SECONDS)
        error.get()?.let { return JSONObject().put("error", it).put("fallback", "settings").toString() }
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val soft = if (Build.VERSION.SDK_INT >= 30) wifi.softApConfiguration else null
        val ssid = soft?.ssid.orEmpty()
        val psk = soft?.passphrase.orEmpty()
        if (ssid.isEmpty()) return JSONObject().put("error", "settings").toString()
        return JSONObject().put("ssid", ssid).put("psk", psk).toString()
    }
}
