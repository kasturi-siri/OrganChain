package com.odat.states

import com.odat.contracts.DonorContract
import com.odat.enums.DonorStatus
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import java.time.Instant

/**
 * DonorState — represents a single organ donor registration on the Corda ledger.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * SECURITY REDESIGN — Full-field AES-256-GCM encryption
 * ─────────────────────────────────────────────────────────────────────────────
 * Previous design: medical matching fields (bloodType, organType, age, etc.)
 * were stored in PLAINTEXT so the matching algorithm could read them directly.
 * This exposed sensitive medical information to any Vault participant.
 *
 * New design: ALL personal and medical fields are encrypted with AES-256-GCM
 * before this state is created. The MatchingAuthority holds the only key
 * authorised to decrypt these ciphertexts. Hospital nodes encrypt on write
 * (using the network-distributed registration key) but NEVER decrypt.
 *
 * Fields stored in plaintext on the ledger:
 *   - linearId        (Corda framework requirement)
 *   - registeredBy    (party reference — structural, not sensitive)
 *   - matchingAuthority (party reference)
 *   - adminNode       (party reference)
 *   - governmentNode  (party reference)
 *   - status          (DonorStatus enum — needed by DonorContract for lifecycle verification)
 *   - registrationTime (audit timestamp)
 *
 * Fields stored encrypted (Base64-encoded AES-256-GCM ciphertext):
 *   - name, contact, bloodType, organType, age, weightKg, heightCm,
 *     isDeceased, location
 *
 * Decryption is performed ONLY by the MatchingAuthority node inside
 * OrganMatchingFlow, producing an in-memory DecryptedDonorData object.
 * Decrypted values are NEVER written back to the ledger.
 *
 * Participants:
 *   registeredBy + matchingAuthority + adminNode + governmentNode
 *   (matchingAuthority needs a vault copy to run matching flows)
 * ─────────────────────────────────────────────────────────────────────────────
 */
@BelongsToContract(DonorContract::class)
data class DonorState(

    // ── Identity ───────────────────────────────────────────────────────────
    val linearId: UniqueIdentifier = UniqueIdentifier(),

    // ── Encrypted personal fields ──────────────────────────────────────────
    /** AES-256-GCM encrypted donor full name. */
    val encryptedName: String,

    /** AES-256-GCM encrypted contact / address info. */
    val encryptedContact: String,

    // ── Encrypted medical matching fields ──────────────────────────────────
    /** AES-256-GCM encrypted BloodType enum name (e.g. "O_POSITIVE"). */
    val encryptedBloodType: String,

    /** AES-256-GCM encrypted OrganType enum name (e.g. "KIDNEY"). */
    val encryptedOrganType: String,

    /** AES-256-GCM encrypted age in years (String representation of Int). */
    val encryptedAge: String,

    /** AES-256-GCM encrypted body weight in kg (String representation of Double). */
    val encryptedWeightKg: String,

    /** AES-256-GCM encrypted height in cm (String representation of Double). */
    val encryptedHeightCm: String,

    /** AES-256-GCM encrypted deceased flag ("true" / "false"). */
    val encryptedIsDeceased: String,

    /** AES-256-GCM encrypted city/hospital location string. */
    val encryptedLocation: String,

    // ── Network parties (plaintext — structural, not sensitive) ───────────
    /** Hospital node that performed the registration. */
    val registeredBy: Party,

    /**
     * MatchingAuthority node — the ONLY node authorised to decrypt this state's
     * medical fields and run the organ matching algorithm. Added as a participant
     * so the MA's vault automatically receives a copy of every DonorState.
     */
    val matchingAuthority: Party,

    /** AdminNode — oversight / confirmation authority. */
    val adminNode: Party,

    /** GovernmentNode — observer for regulatory audit. */
    val governmentNode: Party,

    // ── Status (plaintext — required by DonorContract lifecycle checks) ────
    val status: DonorStatus = DonorStatus.AVAILABLE,

    val registrationTime: Instant = Instant.now()

) : ContractState {

    override val participants: List<AbstractParty>
        get() = listOf(registeredBy, matchingAuthority, adminNode, governmentNode)
}
