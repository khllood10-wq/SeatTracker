package com.example.saudiaseattracker

import android.util.Base64
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

object HybridEncryptor {

    enum class RsaMode {
        PKCS1,
        OAEP_SHA256_MGF1_SHA256,
        OAEP_SHA256_MGF1_SHA1,  // ←←← الـ mixed (الأكثر احتمالاً للفشل!)
    }

    fun encrypt(json: String, rsaPem: String, rsaMode: RsaMode): Pair<String, String> {
        val aesKeyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }

        // AES-256-CBC
        val cipherAes = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipherAes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"), IvParameterSpec(iv))
        val encBodyBytes = iv + cipherAes.doFinal(json.toByteArray(Charsets.UTF_8))
        val bodyHex = encBodyBytes.joinToString("") { "%02x".format(it) }

        // RSA
        val publicKey = parseRsaPublicKey(rsaPem)
        val encKeyBytes = when (rsaMode) {
            RsaMode.PKCS1 -> {
                val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                c.init(Cipher.ENCRYPT_MODE, publicKey)
                c.doFinal(aesKeyBytes)
            }
            RsaMode.OAEP_SHA256_MGF1_SHA256 -> {
                val c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
                c.init(Cipher.ENCRYPT_MODE, publicKey)
                c.doFinal(aesKeyBytes)
            }
            RsaMode.OAEP_SHA256_MGF1_SHA1 -> {
                val c = Cipher.getInstance("RSA/ECB/OAEPPadding")
                val spec = OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA1,
                    PSource.PSpecified.DEFAULT
                )
                c.init(Cipher.ENCRYPT_MODE, publicKey, spec)
                c.doFinal(aesKeyBytes)
            }
        }
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
