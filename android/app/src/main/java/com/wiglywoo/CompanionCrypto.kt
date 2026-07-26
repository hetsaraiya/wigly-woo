package com.wiglywoo

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CompanionCrypto {
    private val aad = "wigly-companion-v1".toByteArray(StandardCharsets.UTF_8)

    fun encrypt(message: JSONObject, secret: String, sender: String): JSONObject {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(secret), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(message.toString().toByteArray(StandardCharsets.UTF_8))
        return JSONObject()
            .put("v", 1)
            .put("sender", sender)
            .put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
    }

    fun decrypt(envelope: JSONObject, secret: String): JSONObject? = runCatching {
        if (envelope.optInt("v") != 1) return null
        val nonce = Base64.decode(envelope.getString("nonce"), Base64.NO_WRAP)
        val encrypted = Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(secret), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        JSONObject(String(cipher.doFinal(encrypted), StandardCharsets.UTF_8))
    }.getOrNull()

    fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun key(secret: String) = SecretKeySpec(
        MessageDigest.getInstance("SHA-256")
            .digest("wigly-key-v1|$secret".toByteArray(StandardCharsets.UTF_8)),
        "AES"
    )
}
