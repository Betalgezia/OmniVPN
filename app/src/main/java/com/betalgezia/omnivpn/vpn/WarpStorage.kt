package com.betalgezia.omnivpn.vpn

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class WarpStorage(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(): WarpAccount? = prefs.getString(KEY, null)?.let { payload ->
        runCatching { WarpAccount.fromJson(decrypt(payload)) }.getOrNull()
    }

    fun set(account: WarpAccount) {
        prefs.edit().putString(KEY, encrypt(account.toJson())).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val blob = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val blob = Base64.decode(encoded, Base64.DEFAULT or Base64.NO_WRAP)
        require(blob.size > 12) { "WARP storage payload is invalid" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return cipher.doFinal(blob.copyOfRange(12, blob.size)).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply { init(256) }.generateKey()
    }

    companion object {
        private const val PREFS = "omnivpn_warp"
        private const val KEY = "account"
        private const val KEY_ALIAS = "OmniVPN.Warp.Storage.v1"
    }
}