package com.betalgezia.omnivpn.vpn

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class VpnConfigStore(context: Context) {

    private val configFile = File(context.filesDir, FILE_NAME)

    fun write(config: String) {
        require(config.isNotBlank()) { "VPN config is empty" }
        configFile.parentFile?.mkdirs()

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(config.toByteArray(Charsets.UTF_8))

        val payload = ByteArray(MAGIC.size + IV_SIZE + encrypted.size)
        MAGIC.copyInto(payload, 0)
        cipher.iv.copyInto(payload, MAGIC.size)
        encrypted.copyInto(payload, MAGIC.size + IV_SIZE)

        val tempFile = File(configFile.parentFile, "$FILE_NAME.tmp")
        FileOutputStream(tempFile).use { output ->
            output.write(payload)
            output.fd.sync()
        }
        check(tempFile.renameTo(configFile)) {
            tempFile.delete()
            "Unable to replace active VPN configuration"
        }
    }

    fun read(): String? {
        if (!configFile.isFile) return null
        val fileSize = configFile.length()
        require(fileSize in 1..MAX_FILE_BYTES) {
            "Active VPN configuration is invalid or too large"
        }
        val payload = FileInputStream(configFile).use { it.readBytes() }
        if (payload.size < MAGIC.size || !payload.copyOf(MAGIC.size).contentEquals(MAGIC)) {
            // Migrate the pre-encryption format on first read. We intentionally
            // return the config even if migration fails; startup remains compatible
            // on devices where the Keystore is temporarily unavailable.
            val legacy = payload.toString(Charsets.UTF_8)
            require(legacy.isNotBlank()) { "Active VPN configuration is empty" }
            runCatching { write(legacy) }
            return legacy
        }

        require(payload.size > MAGIC.size + IV_SIZE) { "Active VPN configuration is corrupt" }
        val ivStart = MAGIC.size
        val ciphertextStart = ivStart + IV_SIZE
        val iv = payload.copyOfRange(ivStart, ciphertextStart)
        val ciphertext = payload.copyOfRange(ciphertextStart, payload.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        return runCatching {
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        }.getOrElse {
            throw IllegalStateException("Unable to decrypt active VPN configuration", it)
        }
    }

    fun clear() {
        configFile.delete()
        File(configFile.parentFile, "$FILE_NAME.tmp").delete()
    }

    private fun secretKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KEY_ALGORITHM, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(KEY_SIZE)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    companion object {
        private const val FILE_NAME = "active-config.json"
        private const val KEY_ALIAS = "OmniVPN.VpnConfig.Storage.v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALGORITHM = "AES"
        private const val KEY_SIZE = 256
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val IV_SIZE = 12
        private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
        private val MAGIC = byteArrayOf(0x4f, 0x56, 0x43, 0x31)
        private val KEY_LOCK = Any()
    }
}
