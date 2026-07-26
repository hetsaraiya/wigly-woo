package com.wiglywoo

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

class WiglyInputMethodService : InputMethodService() {
    private var statusText: TextView? = null
    private val clipboardPoller = Handler(Looper.getMainLooper())
    private val stateListener: (SupabaseRealtimeClient.State) -> Unit = { updateStatus(it) }
    private val pollClipboard = object : Runnable {
        override fun run() {
            CompanionManager.sendCurrentClipboard()
            clipboardPoller.postDelayed(this, 900)
        }
    }
    private val incoming: (JSONObject) -> Unit = { message ->
        if (message.optString("type") == "keyboard") {
            when (message.optString("action")) {
                "insert" -> currentInputConnection?.commitText(message.optString("text"), 1)
                "delete" -> currentInputConnection?.deleteSurroundingText(1, 0)
                "enter" -> currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        CompanionManager.initialize(this)
        CompanionManager.addMessageListener(incoming)
        CompanionManager.addStateListener(stateListener)
        // Android 10+ permits clipboard reads by the selected default IME even
        // while its keyboard window is hidden. Keep polling for the lifetime of
        // the IME service so copies made outside a text field are not missed.
        clipboardPoller.post(pollClipboard)
    }

    override fun onDestroy() {
        clipboardPoller.removeCallbacksAndMessages(null)
        CompanionManager.removeMessageListener(incoming)
        CompanionManager.removeStateListener(stateListener)
        super.onDestroy()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        clipboardPoller.removeCallbacksAndMessages(null)
        clipboardPoller.post(pollClipboard)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
    }

    override fun onCreateInputView(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 26, 34))
            }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "Wigly Woo remote keyboard"
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(context).also {
                    statusText = it
                    it.textSize = 11f
                    it.setPadding(0, dp(3), 0, 0)
                    updateStatus(CompanionManager.state)
                })
            }, LinearLayout.LayoutParams(0, dp(52), 1f))

            addView(TextView(context).apply {
                text = "Choose keyboard"
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(196, 199, 255))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                minWidth = dp(128)
                minHeight = dp(48)
                setPadding(dp(14), 0, dp(14), 0)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(45, 49, 92))
                    cornerRadius = dp(12).toFloat()
                }
                setOnClickListener {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(48)
            ))
        }
    }

    private fun updateStatus(state: SupabaseRealtimeClient.State) {
        val view = statusText ?: return
        when (state) {
            SupabaseRealtimeClient.State.CONNECTED -> {
                view.text = "●  Connected — Mac input appears here"
                view.setTextColor(Color.rgb(87, 207, 145))
            }
            SupabaseRealtimeClient.State.CONNECTING -> {
                view.text = "●  Connecting to your Mac…"
                view.setTextColor(Color.rgb(240, 182, 93))
            }
            SupabaseRealtimeClient.State.ERROR -> {
                view.text = "●  Reconnecting — nearby sharing still works"
                view.setTextColor(Color.rgb(242, 125, 118))
            }
            SupabaseRealtimeClient.State.OFF -> {
                view.text = "●  Companion is offline"
                view.setTextColor(Color.rgb(143, 148, 160))
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
