package com.odat.services

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AESUtils — AES-256-GCM encryption / decryption helper.
 *
 * Used in all registration flows to encrypt PII (name, contact) before
 * the data is written into a Corda State.
 *
 * GCM (Galois/Counter Mode) provides both confidentiality and authenticated
 * integrity — any tampering with the ciphertext is detected on decryption.
 *
 * Key format: 256-bit raw bytes, Base64-encoded for storage/config.
 * IV (nonce): 12 bytes, randomly generated per encryption call.
 * Auth tag: 128 bits (default GCM maximum).
 *
 * Output format: Base64( iv[12] || ciphertext || authTag[16] )
 *
 * FIXES applied:
 *   Finding #14 — Removed keyToBase64(). It was never called anywhere in the
 *                 application — only generateKey(), keyFromBase64(), encrypt(),
 *                 and decrypt() are used at runtime. If a key serialisation
 *                 utility is needed for a bootstrap/setup script in the future,
 *                 it should live in that script, not in the production helper.
 */
object AESUtils {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_SIZE  = 256    // bits
    private const val IV_SIZE   = 12     // bytes (96 bits — GCM recommended)
    private const val TAG_BITS  = 128    // GCM auth-tag length

    // ── Key generation ────────────────────────────────────────────────────────

    /**
     * Generate a fresh AES-256 key.
     * In production this is called once and the result stored in an HSM or
     * secure key-management service — not re-generated on every startup.
     */
    fun generateKey(): SecretKey {
        val gen = KeyGenerator.getInstance("AES")
        gen.init(KEY_SIZE, SecureRandom())
        return gen.generateKey()
    }

    /**
     * Re-hydrate a [SecretKey] from a Base64-encoded raw key bytes string
     * (as stored in environment variables or a vault config).
     */
    fun keyFromBase64(base64Key: String): SecretKey {
        val keyBytes = Base64.getDecoder().decode(base64Key)
        return SecretKeySpec(keyBytes, "AES")
    }

    // FIX #14: keyToBase64() removed — it was dead code (never called in the app).

    // ── Encryption ────────────────────────────────────────────────────────────

    /**
     * Encrypt [plaintext] with [key].
     * Returns a single Base64 string: Base64(iv || ciphertext+authTag).
     */
    fun encrypt(plaintext: String, key: SecretKey): String {
        val iv     = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined   = iv + ciphertext          // prepend IV for decryption
        return Base64.getEncoder().encodeToString(combined)
    }

    // ── Decryption ────────────────────────────────────────────────────────────

    /**
     * Decrypt a Base64 string produced by [encrypt].
     * Throws [javax.crypto.AEADBadTagException] if the ciphertext has been
     * tampered with or the wrong key is supplied.
     */
    fun decrypt(ciphertext: String, key: SecretKey): String {
        val combined = Base64.getDecoder().decode(ciphertext)
        val iv       = combined.copyOfRange(0, IV_SIZE)
        val data     = combined.copyOfRange(IV_SIZE, combined.size)
        val cipher   = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }
}
