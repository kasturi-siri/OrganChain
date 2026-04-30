package com.odat.services

import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

/**
 * KeyVaultService — manages AES-256-GCM keys for the ODaT CorDapp.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * KEY INVENTORY (three keys, three distinct purposes)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * 1. DONOR_PII_KEY    — encrypts donor name & contact at the hospital node.
 *                       Hospital node only. Never used by MatchingAuthority.
 *
 * 2. RECIPIENT_PII_KEY — encrypts recipient name & contact at the hospital node.
 *                        Hospital node only. Never used by MatchingAuthority.
 *
 * 3. MEDICAL_KEY      — encrypts ALL medical matching fields (bloodType,
 *                        organType, age, weight, height, location, etc.).
 *
 *    ┌──────────────────────────────────────────────────────────┐
 *    │  SECURITY MODEL FOR MEDICAL_KEY                          │
 *    │                                                          │
 *    │  Write (encrypt):  Hospital nodes during registration.   │
 *    │  Read  (decrypt):  MatchingAuthority ONLY, during        │
 *    │                    OrganMatchingFlow and               │
 *    │                    NotifyMatchedPartiesFlow.             │
 *    │                                                          │
 *    │  The key is distributed to hospital nodes so they can    │
 *    │  encrypt fields at registration time — but no hospital   │
 *    │  node ever calls AESUtils.decrypt() with this key.       │
 *    │  Only OrganMatchingFlow (which is @StartableByRPC on     │
 *    │  the MatchingAuthority node) calls the decrypt path.     │
 *    │                                                          │
 *    │  For production: replace symmetric key distribution with  │
 *    │  an RSA/ECDH hybrid scheme where hospitals encrypt with   │
 *    │  the MA's public key and only the MA's private key can    │
 *    │  decrypt. This removes key distribution risk entirely.   │
 *    └──────────────────────────────────────────────────────────┘
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * DEVELOPMENT VS PRODUCTION
 * ─────────────────────────────────────────────────────────────────────────────
 * Dev mode: if the alias is absent from node.conf, a fresh in-memory key is
 * generated. This key does NOT survive node restarts — acceptable in dev/test.
 *
 * Production mode: supply all three keys as Base64 strings in node.conf:
 *   custom {
 *     odat_donor_pii_key      = "BASE64_256BIT_KEY"
 *     odat_recipient_pii_key  = "BASE64_256BIT_KEY"
 *     odat_medical_field_key  = "BASE64_256BIT_KEY"
 *   }
 * All participating nodes must share the SAME odat_medical_field_key value,
 * established during the network key ceremony.
 */
@CordaService
class KeyVaultService(private val serviceHub: AppServiceHub) : SingletonSerializeAsToken() {

    private val keyStore: MutableMap<String, SecretKey> = ConcurrentHashMap()

    companion object {
        private const val DONOR_PII_KEY_ALIAS     = "odat_donor_pii_key"
        private const val RECIPIENT_PII_KEY_ALIAS = "odat_recipient_pii_key"

        /**
         * Key alias for medical matching fields.
         * ALL nodes share this key for encryption.
         * ONLY MatchingAuthority uses it for decryption (by operational policy).
         */
        const val MEDICAL_KEY_ALIAS = "odat_medical_field_key"
    }

    // ── Donor PII key ──────────────────────────────────────────────────────

    /**
     * AES-256 key for encrypting donor name and contact information.
     * Used in [RegisterDonorFlow.encryptDonorPii].
     */
    fun getDonorKey(): SecretKey =
        keyStore.getOrPut(DONOR_PII_KEY_ALIAS) { loadOrGenerateKey(DONOR_PII_KEY_ALIAS) }

    // ── Recipient PII key ──────────────────────────────────────────────────

    /**
     * AES-256 key for encrypting recipient name and contact information.
     * Used in [RegisterRecipientFlow.encryptRecipientPii].
     */
    fun getRecipientKey(): SecretKey =
        keyStore.getOrPut(RECIPIENT_PII_KEY_ALIAS) { loadOrGenerateKey(RECIPIENT_PII_KEY_ALIAS) }

    // ── Medical field key ──────────────────────────────────────────────────

    /**
     * AES-256 key for ALL medical matching fields.
     *
     * Called during registration (encrypt path) by hospital nodes.
     * Called during matching (decrypt path) by MatchingAuthority ONLY.
     *
     * Callers should NOT store or cache the returned key outside a
     * non-@Suspendable helper method — the Quasar/Kryo serialization
     * constraint applies here just as it does for the PII keys.
     */
    fun getMedicalKey(): SecretKey =
        keyStore.getOrPut(MEDICAL_KEY_ALIAS) { loadOrGenerateKey(MEDICAL_KEY_ALIAS) }

    // ── Internal ───────────────────────────────────────────────────────────

    private fun loadOrGenerateKey(alias: String): SecretKey {
        return try {
            val base64Key = serviceHub.getAppContext().config.getString(alias)
            AESUtils.keyFromBase64(base64Key)
        } catch (e: Exception) {
            // Config entry absent — ephemeral key (development mode only)
            AESUtils.generateKey()
        }
    }
}
