package com.wiglywoo

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible activity launched from the persistent notification's
 * "Send clipboard" action. A focused activity may read the clipboard even
 * when another keyboard is selected, which the background services cannot.
 */
class SendClipboardActivity : Activity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CompanionManager.initialize(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }
            .getOrNull()?.takeIf { it.isNotEmpty() }
        val message = when {
            text == null -> "Clipboard is empty"
            CompanionManager.sendTextToMac(text) -> "Sent to Mac clipboard"
            else -> "Queued — will deliver once the Mac connection is back"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}
