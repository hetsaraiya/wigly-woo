package com.wiglywoo

import android.graphics.Color
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
    private val clipboardPoller = Handler(Looper.getMainLooper())
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
        // Android 10+ permits clipboard reads by the selected default IME even
        // while its keyboard window is hidden. Keep polling for the lifetime of
        // the IME service so copies made outside a text field are not missed.
        clipboardPoller.post(pollClipboard)
    }

    override fun onDestroy() {
        clipboardPoller.removeCallbacksAndMessages(null)
        CompanionManager.removeMessageListener(incoming)
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
            setPadding(28, 22, 20, 22)
            setBackgroundColor(Color.rgb(28, 30, 36))
            addView(TextView(context).apply {
                text = "Mac keyboard is ready"
                setTextColor(Color.WHITE)
                textSize = 16f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = "Switch keyboard"
                setTextColor(Color.rgb(126, 173, 255))
                setPadding(20, 12, 20, 12)
                setOnClickListener {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                }
            })
        }
    }
}
