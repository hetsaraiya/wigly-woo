package com.wiglywoo.mirror

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.IBinder
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

/**
 * Turns the Wi-Fi hotspot on as the shell uid (which holds TETHER_PRIVILEGED)
 * and reports the SSID and passphrase. TetheringManager and the SoftAP
 * configuration are system APIs, so everything here is reflective.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal object HotspotStarter {
    private const val TETHERING_WIFI = 0

    fun start(context: Context): String {
        val shell = HiddenApi.shell(context)
        val error = AtomicReference<String>()
        val done = CountDownLatch(1)
        val result = runCatching {
            val managerClass = Class.forName("android.net.TetheringManager")
            val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            // Built with the shell context so the caller package matches uid 2000.
            val connector = Supplier<IBinder> {
                Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                    .invoke(null, "tethering") as IBinder
            }
            val manager = managerClass.getDeclaredConstructor(Context::class.java, Supplier::class.java)
                .apply { isAccessible = true }
                .newInstance(shell, connector)
            val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { proxy, method, args ->
                when (method.name) {
                    "onTetheringStarted" -> done.countDown()
                    "onTetheringFailed" -> {
                        error.set("tethering refused (${args?.firstOrNull()})")
                        done.countDown()
                    }
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance proxy === args?.firstOrNull()
                    "toString" -> return@newProxyInstance "WiglyTetheringCallback"
                }
                null
            }
            managerClass.getMethod("startTethering", Int::class.javaPrimitiveType, Executor::class.java, callbackClass)
                .invoke(manager, TETHERING_WIFI, Executor { it.run() }, callback)
        }
        result.exceptionOrNull()?.let { t ->
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            return JSONObject().put("error", cause.message ?: cause.javaClass.simpleName).toString()
        }
        if (!done.await(6, TimeUnit.SECONDS)) error.compareAndSet(null, "tethering did not start")
        error.get()?.let { return JSONObject().put("error", it).toString() }

        val wifi = context.getSystemService(WifiManager::class.java)
        val soft = runCatching { WifiManager::class.java.getMethod("getSoftApConfiguration").invoke(wifi) }.getOrNull()
        val ssid = soft?.let { runCatching { it.javaClass.getMethod("getSsid").invoke(it) as? String }.getOrNull() }.orEmpty()
        val psk = soft?.let { runCatching { it.javaClass.getMethod("getPassphrase").invoke(it) as? String }.getOrNull() }.orEmpty()
        if (ssid.isEmpty()) return JSONObject().put("error", "hotspot is on, but its name could not be read").toString()
        return JSONObject().put("ssid", ssid).put("psk", psk).toString()
    }
}
