package com.odat.services

import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

/**
 * KeyVaultService — Corda singleton service that manages AES-256 symmetric keys
 * for encrypting/decrypting PII fields in DonorState and RecipientState.
 *
 * ─ Development mode ─────────────────────────────────────────────────────────
 * Keys are generated in-memory on first access. They are NOT persisted —
 * if the node restarts, new keys are generated and previously encrypted data
 * becomes un-decryptable. This is acceptable in dev/test.
 *
 * ─ Production mode ──────────────────────────────────────────────────────────
 * Replace the key-retrieval logic with calls to an HSM (Hardware Security Module)
 * or a secrets manager (AWS Secrets Manager / Azure Key Vault / HashiCorp Vault).
 * The node's config file (node.conf) can supply a Base64 key:
 *
 *   custom {
 *     donorAesKey    = "BASE64_ENCODED_256BIT_KEY"
 *     recipientAesKey = "BASE64_ENCODED_256BIT_KEY"
 *   }
 *
 * This service then reads from appConfig instead of generating.
 */
@CordaService
class KeyVaultService(private val serviceHub: AppServiceHub) : SingletonSerializeAsToken() {

    private val keyStore: MutableMap<String, SecretKey> = ConcurrentHashMap()

    companion object {
        private const val DONOR_KEY_ALIAS     = "odat_donor_aes256"
        private const val RECIPIENT_KEY_ALIAS = "odat_recipient_aes256"
    }

    /**
     * Returns the AES-256 key used to encrypt/decrypt donor PII fields.
     * Key is lazily generated and cached for the lifetime of this node process.
     */
    fun getDonorKey(): SecretKey = keyStore.getOrPut(DONOR_KEY_ALIAS) {
        loadOrGenerateKey(DONOR_KEY_ALIAS)
    }

    /**
     * Returns the AES-256 key used to encrypt/decrypt recipient PII fields.
     */
    fun getRecipientKey(): SecretKey = keyStore.getOrPut(RECIPIENT_KEY_ALIAS) {
        loadOrGenerateKey(RECIPIENT_KEY_ALIAS)
    }

    /**
     * In production: read from node.conf → custom block.
     * In dev: fall back to generating a fresh key.
     */
    private fun loadOrGenerateKey(alias: String): SecretKey {
        return try {
            val base64Key = serviceHub.getAppContext().config.getString(alias)
            AESUtils.keyFromBase64(base64Key)
        } catch (e: Exception) {
            // Config key not present — generate ephemeral key (dev mode)
            AESUtils.generateKey()
        }
    }
}
