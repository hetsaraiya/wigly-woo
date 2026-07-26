package com.wiglywoo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Receives selected/shared text directly, avoiding background clipboard limits. */
class SendToMacActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CompanionManager.initialize(this)
        val text = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            else -> null
        }.orEmpty()
        val sent = CompanionManager.sendTextToMac(text)
        Toast.makeText(
            this,
            when {
                text.isEmpty() -> "Nothing to send"
                sent -> "Sent to Mac clipboard"
                else -> "Queued — will deliver once the Mac connection is back"
            },
            Toast.LENGTH_SHORT
        ).show()
        if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text))
        }
        finish()
    }
}
