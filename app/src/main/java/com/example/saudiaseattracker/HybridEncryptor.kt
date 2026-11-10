package com.example.saudiaseattracker

import android.util.Base64
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object HybridEncryptor {

    enum class Variant(
        val label: String,
        val rsaTransform: String,
        val keyFormat: KeyFormat,   // كيف نغلف AES key قبل RSA
        val aesMode: AesMode
    ) {
        // ──── مجرب سابقاً (لسه موجودة للمقارنة) ────
        OAEP256_RAW_CBC("OAEP256+RAW+CBC", "RSA/ECB/OAEPWithSHA-256AndMGF1Padding", KeyFormat.RAW, AesMode.CBC_IV_FIRST),
        PKCS1_RAW_CBC("PKCS1+RAW+CBC", "RSA/ECB/PKCS1Padding", KeyFormat.RAW, AesMode.CBC_IV_FIRST),

        // ──── جديد: AES key كنص HEX/A-BASE64 قبل RSA (نمط jsencrypt الشائع) ────
        PKCS1_HEX64_CBC("PKCS1+HEX64+CBC", "RSA/ECB/PKCS1Padding", KeyFormat.HEX64, AesMode.CBC_IV_FIRST),
        PKCS1_B64_CBC("PKCS1+B64+CBC", "RSA/ECB/PKCS1Padding", KeyFormat.BASE64, AesMode.CBC_IV_FIRST),
        PKCS1_ASCII32_CBC("PKCS1+ASCII32+CBC", "RSA/ECB/PKCS1Padding", KeyFormat.ASCII32, AesMode.CBC_IV_FIRST),
        OAEP256_HEX64_CBC("OAEP256+HEX64+CBC", "RSA/ECB/OAEPWithSHA-256AndMGF1Padding", KeyFormat.HEX64, AesMode.CBC_IV_FIRST),

        // ──── AES-GCM بدل CBC ────
        PKCS1_HEX64_GCM("PKCS1+HEX64+GCM", "RSA/ECB/PKCS1Padding", KeyFormat.HEX64, AesMode.GCM_IV_FIRST),
        OAEP256_HEX64_GCM("OAEP256+HEX64+GCM", "RSA/ECB/OAEPWithSHA-256AndMGF1Padding", KeyFormat.HEX64, AesMode.GCM_IV_FIRST),
    }

    enum class KeyFormat { RAW, HEX64, BASE64, ASCII32 }
    enum class AesMode { CBC_IV_FIRST, GCM_IV_FIRST }

    fun encrypt(json: String, rsaPem: String, variant: Variant): Pair<String, String> {
        // 1) توليد AES-256 key عشوائي (32 byte)
        val aesKeyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }

        // 2) تغليف المفتاح حسب الصيغة
        val keyPlainBytes: ByteArray = when (variant.keyFormat) {
            KeyFormat.RAW     -> aesKeyBytes
            KeyFormat.HEX64   -> aesKeyBytes.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.UTF_8)
            KeyFormat.BASE64  -> Base64.encodeToString(aesKeyBytes, Base64.NO_WRAP).toByteArray(Charsets.UTF_8)
            KeyFormat.ASCII32 -> aesKeyBytes.copyOf(32) // 32 bytes عشوائية لكن تُفسر كنص
        }

        // 3) تشفير AES key بـ RSA
        val publicKey = parseRsaPublicKey(rsaPem)
        val rsaCipher = Cipher.getInstance(variant.rsaTransform)
        rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encKeyBytes = rsaCipher.doFinal(keyPlainBytes)
        val keyB64 = Base64.encodeToString(encKeyBytes, Base64.NO_WRAP)

        // 4) تشفير JSON بـ AES
        val bodyBytes: ByteArray = when (variant.aesMode) {
            AesMode.CBC_IV_FIRST -> encryptCbc(json, aesKeyBytes)
            AesMode.GCM_IV_FIRST -> encryptGcm(json, aesKeyBytes)
        }
        val bodyHex = bodyBytes.joinToString("") { "%02x".format(it) }

        return Pair(keyB64, bodyHex)
    }

    private fun encryptCbc(json: String, keyBytes: ByteArray): ByteArray {
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        return iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
    }

    private fun encryptGcm(json: String, keyBytes: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
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
