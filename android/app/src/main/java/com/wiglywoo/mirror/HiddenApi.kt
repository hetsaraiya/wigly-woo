package com.wiglywoo.mirror

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.os.UserManager
import android.util.Log
import android.view.Display
import android.view.InputEvent
import android.view.Surface

/**
 * Every hidden API used by mirroring lives here. Each call is probed and a
 * failure disables that one feature.
 *
 * The Shizuku process runs as uid 2000 but its context carries our package
 * name, and system services reject a package that does not match the calling
 * uid. [shell] wraps the context so those services see com.android.shell.
 * The approach follows scrcpy (Apache-2.0); this file is an original
 * implementation.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi")
object HiddenApi {
    private const val TAG = "WiglyMirror"
    const val SHELL_PACKAGE = "com.android.shell"
    const val SHELL_UID = 2000

    private const val FLAG_PUBLIC = 1 shl 0
    private const val FLAG_PRESENTATION = 1 shl 1
    private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    private const val FLAG_AUTO_MIRROR = 1 shl 4
    private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
    private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    private const val FLAG_TRUSTED = 1 shl 10
    private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12

    /** A context that system services accept from the shell uid. */
    fun shell(base: Context): Context = ShellContext(base)

    @SuppressLint("NewApi") // getAttributionSource is only called on Android 12+
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL_PACKAGE
        override fun getOpPackageName() = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
    }

    /**
     * A display that mirrors the phone screen into [surface]. Android 14's
     * static helper is built for exactly this (scrcpy uses it). A PUBLIC
     * display is avoided: One UI treats it as a second screen with its own
     * empty content instead of a mirror.
     */
    fun mirrorDisplay(context: Context, name: String, w: Int, h: Int, dpi: Int, surface: Surface): VirtualDisplay? {
        runCatching {
            return DisplayManager::class.java.getMethod(
                "createVirtualDisplay", String::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Surface::class.java,
            ).invoke(null, name, w, h, Display.DEFAULT_DISPLAY, surface) as VirtualDisplay
        }.onFailure { Log.w(TAG, "static mirror display", it) }
        return runCatching { displayManager(context).createVirtualDisplay(name, w, h, dpi, surface, FLAG_AUTO_MIRROR) }
            .onFailure { Log.w(TAG, "auto-mirror display", it) }.getOrNull()
    }

    /** A separate display that shows only the apps launched onto it. */
    fun appDisplay(context: Context, name: String, w: Int, h: Int, dpi: Int, surface: Surface): VirtualDisplay? {
        var flags = FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
            FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
        if (Build.VERSION.SDK_INT >= 33) flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
        return runCatching { displayManager(context).createVirtualDisplay(name, w, h, dpi, surface, flags) }
            .onFailure { Log.w(TAG, "app display", it) }.getOrNull()
    }

    private fun displayManager(context: Context): DisplayManager =
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(shell(context))

    // ── input ──

    private val injector: Pair<Any, java.lang.reflect.Method>? by lazy {
        // Android 14 moved injection to InputManagerGlobal.
        for (name in listOf("android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager")) {
            val found = runCatching {
                val cls = Class.forName(name)
                val instance = cls.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)!!
                val inject = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                instance to inject
            }.getOrNull()
            if (found != null) return@lazy found
        }
        Log.w(TAG, "input injection unavailable")
        null
    }

    private val setDisplayIdMethod by lazy {
        runCatching { InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }.getOrNull()
    }

    /** Injects without waiting for the target app (mode 0 = async). */
    fun inject(event: InputEvent, displayId: Int = Display.DEFAULT_DISPLAY): Boolean {
        val (instance, method) = injector ?: return false
        if (displayId != Display.DEFAULT_DISPLAY) runCatching { setDisplayIdMethod?.invoke(event, displayId) }
        return runCatching { method.invoke(instance, event, 0) as Boolean }.getOrDefault(false)
    }

    // ── panel power ──

    private const val POWER_OFF = 0
    private const val POWER_NORMAL = 2

    private val surfaceControl by lazy { runCatching { Class.forName("android.view.SurfaceControl") }.getOrNull() }

    /** Physical display tokens. Android 14 moved the lookup to services.jar's DisplayControl. */
    private fun displayTokens(): List<IBinder> {
        val sc = surfaceControl ?: return emptyList()
        runCatching {
            val m = sc.getMethod("getInternalDisplayToken")
            return listOfNotNull(m.invoke(null) as? IBinder)
        }
        runCatching {
            val m = sc.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType)
            return listOfNotNull(m.invoke(null, 0) as? IBinder)
        }
        return runCatching {
            val control = displayControl() ?: return emptyList()
            val ids = control.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
            val token = control.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            ids.toList().mapNotNull { token.invoke(null, it) as? IBinder }
        }.onFailure { Log.w(TAG, "display tokens", it) }.getOrDefault(emptyList())
    }

    private fun displayControl(): Class<*>? {
        val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
        val create = factory.getDeclaredMethod(
            "createClassLoader", String::class.java, String::class.java, String::class.java,
            ClassLoader::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java,
        )
        val loader = create.invoke(null, "/system/framework/services.jar", null, null,
            ClassLoader.getSystemClassLoader(), 0, true, null) as ClassLoader
        val control = loader.loadClass("com.android.server.display.DisplayControl")
        val load = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
        load.isAccessible = true
        load.invoke(Runtime.getRuntime(), control, "android_servers")
        return control
    }

    /** Turns the physical panel off or on while the phone keeps rendering. */
    fun setDisplayPower(on: Boolean): Boolean {
        val sc = surfaceControl ?: return false
        val tokens = displayTokens()
        if (tokens.isEmpty()) return false
        return runCatching {
            val set = sc.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
            tokens.forEach { set.invoke(null, it, if (on) POWER_NORMAL else POWER_OFF) }
            true
        }.onFailure { Log.w(TAG, "display power", it) }.getOrDefault(false)
    }

    fun userUnlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: false

    fun defaultDisplaySize(context: Context): Triple<Int, Int, Int> {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        val fallback = context.resources.displayMetrics
        return Triple(
            metrics.widthPixels.takeIf { it > 0 } ?: fallback.widthPixels,
            metrics.heightPixels.takeIf { it > 0 } ?: fallback.heightPixels,
            metrics.densityDpi.takeIf { it > 0 } ?: fallback.densityDpi,
        )
    }
}
