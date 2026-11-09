package com.example.saudiaseattracker

import android.util.Base64
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object HybridEncryptor {

    /**
     * يعيد (keyBase64, bodyHex) جاهزة للإرسال
     */
    fun encrypt(json: String, rsaPem: String): Pair<String, String> {
        // 1) مفتاح AES-256 عشوائي + IV عشوائي
        val aesKeyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }

        // 2) تشفير JSON بـ AES/CBC/PKCS5
        val cipherAes = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipherAes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"), IvParameterSpec(iv))
        val encBodyBytes = iv + cipherAes.doFinal(json.toByteArray(Charsets.UTF_8))
        val bodyHex = encBodyBytes.joinToString("") { "%02x".format(it) }

        // 3) تشفير مفتاح AES بـ RSA public key
        val publicKey = parseRsaPublicKey(rsaPem)

        val rsaCipher = tryOaep(publicKey, "SHA-256")
            ?: tryOaep(publicKey, "SHA-1")
            ?: tryPkcs1(publicKey)
            ?: throw IllegalStateException("No RSA cipher available")

        val encKeyBytes = rsaCipher.doFinal(aesKeyBytes)
        val keyBase64 = Base64.encodeToString(encKeyBytes, Base64.NO_WRAP)

        return Pair(keyBase64, bodyHex)
    }

    private fun tryOaep(key: PublicKey, sha: String): Cipher? = try {
        val c = Cipher.getInstance("RSA/ECB/OAEPWith${sha}AndMGF1Padding")
        c.init(Cipher.ENCRYPT_MODE, key)
        c
    } catch (_: Throwable) { null }

    private fun tryPkcs1(key: PublicKey): Cipher? = try {
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.ENCRYPT_MODE, key)
        c
    } catch (_: Throwable) { null }

    private fun parseRsaPublicKey(pem: String): PublicKey {
        val cleaned = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("-----BEGIN RSA PUBLIC KEY-----", "")
            .replace("-----END RSA PUBLIC KEY-----", "")
            .replace("\n", "").replace("\r", "").trim()
        val decoded = Base64.decode(cleaned, Base64.DEFAULT)
        val spec = X509EncodedKeySpec(decoded)
        val kf = java.security.KeyFactory.getInstance("RSA")
        return kf.generatePublic(spec)
    }
}
