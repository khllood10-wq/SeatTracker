package com.example.saudiaseattracker

import android.util.Base64
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object HybridEncryptor {

    enum class Variant(val label: String, val rsaTransform: String, val aesKeyBytes: Int) {
        OAEP256_AES256("OAEP256+AES256", "RSA/ECB/OAEPWithSHA-256AndMGF1Padding", 32),
        PKCS1_AES256("PKCS1+AES256", "RSA/ECB/PKCS1Padding", 32),
        OAEP256_AES128("OAEP256+AES128", "RSA/ECB/OAEPWithSHA-256AndMGF1Padding", 16),
        PKCS1_AES128("PKCS1+AES128", "RSA/ECB/PKCS1Padding", 16),
        OAEP1_AES256("OAEP1+AES256", "RSA/ECB/OAEPWithSHA-1AndMGF1Padding", 32),
        OAEP1_AES128("OAEP1+AES128", "RSA/ECB/OAEPWithSHA-1AndMGF1Padding", 16),
    }

    fun encrypt(json: String, rsaPem: String, variant: Variant): Pair<String, String> {
        val aesKeyBytes = ByteArray(variant.aesKeyBytes).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }

        val cipherAes = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipherAes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"), IvParameterSpec(iv))
        val encBodyBytes = iv + cipherAes.doFinal(json.toByteArray(Charsets.UTF_8))
        val bodyHex = encBodyBytes.joinToString("") { "%02x".format(it) }

        val publicKey = parseRsaPublicKey(rsaPem)
        val rsaCipher = Cipher.getInstance(variant.rsaTransform)
        rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encKeyBytes = rsaCipher.doFinal(aesKeyBytes)
        val keyB64 = Base64.encodeToString(encKeyBytes, Base64.NO_WRAP)

        return Pair(keyB64, bodyHex)
    }

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
