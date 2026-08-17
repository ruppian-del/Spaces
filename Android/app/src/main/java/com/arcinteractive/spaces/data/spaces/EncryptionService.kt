package com.arcinteractive.spaces.data.spaces

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedMessagePayload(
    val ciphertext: String,
    val nonce: String,
    val tag: String
)

class EncryptionService {
    private val keyStoreProvider = "AndroidKeyStore"
    private val keyAliasPrefix = "spaces_e2ee_identity_"
    private val random = SecureRandom()
    private val cachedSpaceKeys = mutableMapOf<String, ByteArray>()

    fun ensurePublicKey(userId: String): String {
        val publicKey = loadPublicKey(userId) ?: generateKeyPair(userId).public
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    fun cachedSpaceKey(spaceId: String): ByteArray? = cachedSpaceKeys[spaceId]?.copyOf()

    fun cacheSpaceKey(spaceId: String, key: ByteArray) {
        cachedSpaceKeys[spaceId] = key.copyOf()
    }

    fun generateSpaceKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun generateSpaceKeyBase64(): String = Base64.encodeToString(generateSpaceKey(), Base64.NO_WRAP)

    fun encodeSpaceKey(key: ByteArray): String = Base64.encodeToString(key, Base64.NO_WRAP)

    fun decodeSpaceKey(encoded: String): ByteArray = Base64.decode(encoded, Base64.NO_WRAP)

    fun wrapSpaceKey(
        spaceKey: ByteArray,
        recipientPublicKeyBase64: String,
        senderUserId: String
    ): String {
        val senderPrivateKey = requirePrivateKey(senderUserId)
        val recipientPublicKey = decodePublicKey(recipientPublicKeyBase64)
        val wrappingKey = deriveWrappingKey(senderPrivateKey, recipientPublicKey)
        return encryptCombined(spaceKey, wrappingKey)
    }

    fun unwrapSpaceKey(
        wrappedKey: String,
        senderPublicKeyBase64: String,
        recipientUserId: String
    ): ByteArray {
        val recipientPrivateKey = requirePrivateKey(recipientUserId)
        val senderPublicKey = decodePublicKey(senderPublicKeyBase64)
        val wrappingKey = deriveWrappingKey(recipientPrivateKey, senderPublicKey)
        val parts = wrappedKey.split(".")
        return if (parts.size == 3) {
            decrypt(
                ciphertext = parts[1],
                nonce = parts[0],
                tag = parts[2],
                keyBytes = wrappingKey
            )
        } else {
            decryptCombined(wrappedKey, wrappingKey)
        }
    }

    fun encryptText(text: String, spaceKey: ByteArray): EncryptedMessagePayload {
        return encrypt(text.toByteArray(StandardCharsets.UTF_8), spaceKey)
    }

    fun decryptText(ciphertext: String, nonce: String, tag: String? = null, spaceKey: ByteArray): String {
        return decrypt(ciphertext, nonce, tag, spaceKey).toString(StandardCharsets.UTF_8)
    }

    fun encryptBytes(bytes: ByteArray, spaceKey: ByteArray): EncryptedMessagePayload {
        return encrypt(bytes, spaceKey)
    }

    fun decryptBytes(ciphertext: String, nonce: String, spaceKey: ByteArray): ByteArray {
        return decrypt(ciphertext, nonce, null, spaceKey)
    }

    private fun deriveWrappingKey(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(publicKey, true)
        return MessageDigest.getInstance("SHA-256").digest(keyAgreement.generateSecret())
    }

    private fun encrypt(plaintext: ByteArray, keyBytes: ByteArray): EncryptedMessagePayload {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(12).also(random::nextBytes)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
        val encrypted = cipher.doFinal(plaintext)
        return EncryptedMessagePayload(
            ciphertext = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
            tag = ""
        )
    }

    private fun encryptCombined(plaintext: ByteArray, keyBytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(12).also(random::nextBytes)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
        val encrypted = cipher.doFinal(plaintext)
        val combined = ByteArray(nonce.size + encrypted.size)
        System.arraycopy(nonce, 0, combined, 0, nonce.size)
        System.arraycopy(encrypted, 0, combined, nonce.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(ciphertext: String, nonce: String, tag: String?, keyBytes: ByteArray): ByteArray {
        val nonceBytes = Base64.decode(nonce, Base64.NO_WRAP)
        val ciphertextBytes = Base64.decode(ciphertext, Base64.NO_WRAP)
        val encrypted = if (tag.isNullOrBlank()) {
            ciphertextBytes
        } else {
            val tagBytes = Base64.decode(tag, Base64.NO_WRAP)
            ByteArray(ciphertextBytes.size + tagBytes.size).also {
                System.arraycopy(ciphertextBytes, 0, it, 0, ciphertextBytes.size)
                System.arraycopy(tagBytes, 0, it, ciphertextBytes.size, tagBytes.size)
            }
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonceBytes))
        return cipher.doFinal(encrypted)
    }

    private fun decryptCombined(combinedCiphertext: String, keyBytes: ByteArray): ByteArray {
        val combined = Base64.decode(combinedCiphertext, Base64.NO_WRAP)
        require(combined.size > 12) { "Wrapped key payload is invalid." }
        val nonce = combined.copyOfRange(0, 12)
        val encrypted = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(encrypted)
    }

    private fun generateKeyPair(userId: String): java.security.KeyPair {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, keyStoreProvider)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias(userId),
            KeyProperties.PURPOSE_AGREE_KEY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .build()
        generator.initialize(spec)
        return generator.generateKeyPair()
    }

    private fun requirePrivateKey(userId: String): PrivateKey {
        return loadPrivateKey(userId) ?: generateKeyPair(userId).private
    }

    private fun loadPrivateKey(userId: String): PrivateKey? {
        val keyStore = KeyStore.getInstance(keyStoreProvider).apply { load(null) }
        return keyStore.getKey(keyAlias(userId), null) as? PrivateKey
    }

    private fun loadPublicKey(userId: String): PublicKey? {
        val keyStore = KeyStore.getInstance(keyStoreProvider).apply { load(null) }
        return keyStore.getCertificate(keyAlias(userId))?.publicKey
    }

    private fun decodePublicKey(encoded: String): PublicKey {
        val keyData = Base64.decode(encoded, Base64.NO_WRAP)
        val keyFactory = KeyFactory.getInstance("EC")

        runCatching {
            return keyFactory.generatePublic(X509EncodedKeySpec(keyData))
        }

        if (keyData.size == 65 && keyData.first() == 0x04.toByte()) {
            val params = (loadPublicKeyFromKeystore() as? ECPublicKey)?.params
                ?: throw IllegalArgumentException("No EC parameters available for raw public key decoding.")
            val coordinateLength = 32
            val x = java.math.BigInteger(1, keyData.copyOfRange(1, 1 + coordinateLength))
            val y = java.math.BigInteger(1, keyData.copyOfRange(1 + coordinateLength, keyData.size))
            val keySpec = ECPublicKeySpec(ECPoint(x, y), params)
            return keyFactory.generatePublic(keySpec)
        }

        throw IllegalArgumentException("Unsupported public key format.")
    }

    private fun keyAlias(userId: String): String = "$keyAliasPrefix$userId"

    private fun loadPublicKeyFromKeystore(): PublicKey? {
        val keyStore = KeyStore.getInstance(keyStoreProvider).apply { load(null) }
        val aliases = keyStore.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            if (alias.startsWith(keyAliasPrefix)) {
                return keyStore.getCertificate(alias)?.publicKey
            }
        }
        return null
    }
}
