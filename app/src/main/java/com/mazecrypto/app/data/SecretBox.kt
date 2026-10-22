package com.mazecrypto.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The few things that must not sit on disk in the clear — today only Monero
 * private *view* keys (they cannot spend, but they reveal every incoming
 * payment). Each value is sealed with AES-256-GCM under a key that lives in the
 * Android Keystore (hardware-backed where the phone has it) and never leaves it;
 * the file is excluded from every backup, and the key would not survive one anyway.
 */
class SecretBox(private val file: File) {
    private val alias = "maze-crypto-secrets-v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    @Synchronized
    private fun load(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }

    @Synchronized
    private fun store(o: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    @Synchronized
    fun put(name: String, value: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(name.toByteArray())
        val sealed = c.iv + c.doFinal(value)
        store(load().put(name, Base64.encodeToString(sealed, Base64.NO_WRAP)))
    }

    @Synchronized
    fun get(name: String): ByteArray? {
        val b64 = load().optString(name).takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val sealed = Base64.decode(b64, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
            c.updateAAD(name.toByteArray())
            c.doFinal(sealed, 12, sealed.size - 12)
        }.getOrNull()
    }

    @Synchronized
    fun remove(name: String) {
        val o = load()
        if (o.has(name)) { o.remove(name); store(o) }
    }
}
