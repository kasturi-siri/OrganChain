package com.odat.states

import com.odat.contracts.RecipientContract
import com.odat.enums.RecipientStatus
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import java.time.Instant

/**
 * RecipientState — represents a patient awaiting organ transplantation.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * SECURITY REDESIGN — Full-field AES-256-GCM encryption
 * ─────────────────────────────────────────────────────────────────────────────
 * All personal and medical fields are now AES-256-GCM encrypted before this
 * state is written to the Corda ledger.  See DonorState for the full
 * architectural explanation.
 *
 * Fields stored in plaintext:
 *   - linearId, party references, status, registrationTime
 *
 * Fields stored encrypted:
 *   - name, contact, bloodType, organNeeded, age, weightKg, heightCm,
 *     conditionScore, serialNumber, hasPairedDonor, location
 *
 * Decryption is performed ONLY by the MatchingAuthority inside
 * OrganMatchingFlow, producing an in-memory [DecryptedRecipientData].
 * ─────────────────────────────────────────────────────────────────────────────
 */
@BelongsToContract(RecipientContract::class)
data class RecipientState(

    // ── Identity ───────────────────────────────────────────────────────────
    val linearId: UniqueIdentifier = UniqueIdentifier(),

    // ── Encrypted personal fields ──────────────────────────────────────────
    /** AES-256-GCM encrypted recipient full name. */
    val encryptedName: String,

    /** AES-256-GCM encrypted contact / address info. */
    val encryptedContact: String,

    // ── Encrypted medical matching fields ──────────────────────────────────
    /** AES-256-GCM encrypted BloodType enum name. */
    val encryptedBloodType: String,

    /** AES-256-GCM encrypted OrganType enum name (organ needed). */
    val encryptedOrganNeeded: String,

    /** AES-256-GCM encrypted age in years (String of Int). */
    val encryptedAge: String,

    /** AES-256-GCM encrypted weight in kg (String of Double). */
    val encryptedWeightKg: String,

    /** AES-256-GCM encrypted height in cm (String of Double). */
    val encryptedHeightCm: String,

    /**
     * AES-256-GCM encrypted clinical urgency score 1–10 (String of Int).
     * 10 = most critical — contributes up to 50 pts in matching algorithm.
     */
    val encryptedConditionScore: String,

    /**
     * AES-256-GCM encrypted waitlist serial number (String of Int).
     * Earlier registrations receive a small tie-breaking advantage.
     */
    val encryptedSerialNumber: String,

    /**
     * AES-256-GCM encrypted paired-donor flag ("true"/"false").
     * True enables the Kidney Paired Exchange (KPE) scoring bonus of +20 pts.
     */
    val encryptedHasPairedDonor: String,

    /** AES-256-GCM encrypted city / hospital location. */
    val encryptedLocation: String,

    // ── Network parties (plaintext — structural) ──────────────────────────
    val registeredBy:       Party,
    val matchingAuthority:  Party,
    val adminNode:          Party,
    val governmentNode:     Party,

    // ── Status (plaintext — needed by RecipientContract lifecycle checks) ──
    val status: RecipientStatus = RecipientStatus.WAITING,

    val registrationTime: Instant = Instant.now()

) : ContractState {

    override val participants: List<AbstractParty>
        get() = listOf(registeredBy, matchingAuthority, adminNode, governmentNode)
}
