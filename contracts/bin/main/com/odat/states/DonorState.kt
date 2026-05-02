package com.odat.states

import com.odat.contracts.DonorContract
import com.odat.enums.BloodType
import com.odat.enums.DonorStatus
import com.odat.enums.OrganType
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import java.time.Instant

/**
 * DonorState — represents a single organ donor registration on the Corda ledger.
 *
 * PII fields (name, address) are stored AES-256-GCM encrypted as Base64 strings.
 * Medical compatibility fields (bloodType, organType, age, weight, height) are
 * stored in plaintext because the matching algorithm needs them for scoring.
 *
 * Participants: registering hospital + AdminNode + GovernmentNode
 * (GovernmentNode holds an observer copy for regulatory oversight).
 */
@BelongsToContract(DonorContract::class)
data class DonorState(

    // ── Identity ──────────────────────────────────────────────────
    val linearId: UniqueIdentifier = UniqueIdentifier(),

    /** AES-256-GCM encrypted donor full name (Base64 ciphertext). */
    val encryptedName: String,

    /** AES-256-GCM encrypted contact / address info. */
    val encryptedContact: String,

    // ── Medical matching fields ───────────────────────────────────
    val bloodType: BloodType,
    val organType: OrganType,

    /** Age in years — used for age-compatibility scoring. */
    val age: Int,

    /** Body weight in kilograms — used for size-compatibility (BMI diff). */
    val weightKg: Double,

    /** Height in centimetres — used for BMI calculation. */
    val heightCm: Double,

    /**
     * True if the donor is deceased (cadaveric).
     * Deceased donors trigger a location-proximity check in the algorithm.
     */
    val isDeceased: Boolean,

    /** City / hospital where the organ is physically located. */
    val location: String,

    // ── Network parties ───────────────────────────────────────────
    /** Hospital node that performed the registration (e.g. HospitalA). */
    val registeredBy: Party,

    /** AdminNode — receives state copy for oversight. */
    val adminNode: Party,

    /** GovernmentNode — receives state copy for audit / compliance. */
    val governmentNode: Party,

    // ── Status ────────────────────────────────────────────────────
    val status: DonorStatus = DonorStatus.AVAILABLE,

    val registrationTime: Instant = Instant.now()

) : ContractState {

    override val participants: List<AbstractParty>
        get() = listOf(registeredBy, adminNode, governmentNode)

    /** Computed BMI — used inside the size-compatibility check. */
    val bmi: Double
        get() = weightKg / ((heightCm / 100.0) * (heightCm / 100.0))
}
