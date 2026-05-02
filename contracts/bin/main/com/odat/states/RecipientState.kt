package com.odat.states

import com.odat.contracts.RecipientContract
import com.odat.enums.BloodType
import com.odat.enums.OrganType
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
 * PII is AES-256-GCM encrypted before this state is created in any flow.
 * All scoring fields are in plaintext so the matching algorithm can compute
 * the weighted score without decryption (minimising key exposure).
 */
@BelongsToContract(RecipientContract::class)
data class RecipientState(

    // ── Identity ──────────────────────────────────────────────────
    val linearId: UniqueIdentifier = UniqueIdentifier(),

    /** AES-256-GCM encrypted recipient full name. */
    val encryptedName: String,

    /** AES-256-GCM encrypted contact / address info. */
    val encryptedContact: String,

    // ── Medical matching fields ───────────────────────────────────
    val bloodType: BloodType,
    val organNeeded: OrganType,

    val age: Int,
    val weightKg: Double,
    val heightCm: Double,

    /**
     * Clinical urgency score 1–10 (10 = critical).
     * Contributes up to 50 points in the matching algorithm.
     */
    val conditionScore: Int,

    /**
     * Waitlist serial number — earlier registrations get a small tie-breaking
     * advantage (subtracted as 0.001 × serialNumber in scoring).
     */
    val serialNumber: Int,

    /**
     * True if this recipient has a willing but incompatible paired donor —
     * enables Kidney Paired Exchange (KPE) scoring bonus of +20 points.
     */
    val hasPairedDonor: Boolean,

    /** City where recipient is being treated — used for location scoring. */
    val location: String,

    // ── Network parties ───────────────────────────────────────────
    val registeredBy: Party,
    val adminNode: Party,
    val governmentNode: Party,

    // ── Status ────────────────────────────────────────────────────
    val status: RecipientStatus = RecipientStatus.WAITING,

    val registrationTime: Instant = Instant.now()

) : ContractState {

    override val participants: List<AbstractParty>
        get() = listOf(registeredBy, adminNode, governmentNode)

    val bmi: Double
        get() = weightKg / ((heightCm / 100.0) * (heightCm / 100.0))
}
