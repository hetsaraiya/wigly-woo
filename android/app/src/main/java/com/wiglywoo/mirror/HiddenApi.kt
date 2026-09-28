package com.wiglywoo.mirror

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.os.UserManager
import android.view.Display
import android.view.InputEvent
import android.view.Surface
import org.json.JSONObject

/**
 * Every hidden API used by mirroring lives here. Each call is probed and a
 * failure disables that one feature.
 *
 * Virtual-display mirroring and input injection follow the approach proved by
 * scrcpy (Apache-2.0). This file is an original implementation.
 */
object HiddenApi {
    const val FLAG_PUBLIC = 1
    const val FLAG_PRESENTATION = 2
    const val FLAG_OWN_CONTENT_ONLY = 8
    const val FLAG_AUTO_MIRROR = 16

    data class Probe(
        val virtualDisplay: Boolean,
        val inject: Boolean,
        val displayPower: Boolean,
        val audioCapture: Boolean,
        val flexResize: Boolean,
    )

    fun probe(context: Context?): Probe {
        val sdk = Build.VERSION.SDK_INT
        return Probe(
            virtualDisplay = createDisplayMethod() != null,
            inject = injectMethod() != null,
            displayPower = powerMethod() != null,
            audioCapture = sdk >= 29,
            flexResize = sdk >= 35,
        )
    }

    fun capabilitiesJson(context: Context?): String {
        val p = probe(context)
        return JSONObject()
            .put("virtualDisplay", p.virtualDisplay)
            .put("inject", p.inject)
            .put("displayPower", p.displayPower)
            .put("audioCapture", p.audioCapture)
            .put("flexResize", p.flexResize)
            .put("sdk", Build.VERSION.SDK_INT)
            .toString()
    }

    fun createVirtualDisplay(
        context: Context,
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
    ): VirtualDisplay? {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return null
        val method = createDisplayMethod() ?: return null
        return runCatching {
            method.invoke(dm, name, width, height, dpi, surface, flags) as VirtualDisplay
        }.getOrNull()
    }

    fun inject(event: InputEvent): Boolean {
        val method = injectMethod() ?: return false
        val manager = inputManager() ?: return false
        return runCatching { method.invoke(manager, event, 0) as Boolean }.getOrDefault(false)
    }

    fun setDisplayPower(on: Boolean): Boolean {
        val (setMode, tokenMethod) = powerMethod() ?: return false
        return runCatching {
            val token = if (tokenMethod.parameterCount == 0) {
                tokenMethod.invoke(null)
            } else {
                tokenMethod.invoke(null, 0)
            } as? IBinder ?: return false
            setMode.invoke(null, token, if (on) 2 else 0)
            true
        }.getOrDefault(false)
    }

    fun userUnlocked(context: Context): Boolean {
        val um = context.getSystemService(UserManager::class.java) ?: return false
        return um.isUserUnlocked
    }

    private fun createDisplayMethod() = runCatching {
        DisplayManager::class.java.getMethod(
            "createVirtualDisplay",
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Surface::class.java,
            Int::class.javaPrimitiveType,
        )
    }.getOrNull()

    private fun inputManager(): Any? = runCatching {
        val cls = Class.forName("android.hardware.input.InputManager")
        cls.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
    }.getOrNull()

    private fun injectMethod() = runCatching {
        val cls = Class.forName("android.hardware.input.InputManager")
        cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }.getOrNull()

    private fun powerMethod(): Pair<java.lang.reflect.Method, java.lang.reflect.Method>? = runCatching {
        val sc = Class.forName("android.view.SurfaceControl")
        val token = sc.methods.first { it.name == "getInternalDisplayToken" || it.name == "getBuiltInDisplay" }
        token.isAccessible = true
        val setMode = sc.getDeclaredMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
        setMode.isAccessible = true
        setMode to token
    }.getOrNull()

    fun defaultDisplaySize(context: Context): Triple<Int, Int, Int> {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        val w = if (metrics.widthPixels > 0) metrics.widthPixels else context.resources.displayMetrics.widthPixels
        val h = if (metrics.heightPixels > 0) metrics.heightPixels else context.resources.displayMetrics.heightPixels
        val dpi = if (metrics.densityDpi > 0) metrics.densityDpi else context.resources.displayMetrics.densityDpi
        return Triple(w, h, dpi)
    }
}
