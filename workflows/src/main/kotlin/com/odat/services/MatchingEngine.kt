package com.odat.services

import com.odat.states.DecryptedDonorData
import com.odat.states.DecryptedRecipientData

/**
 * MatchingEngine — stateless implementation of Algorithm 1
 * (Donor-Recipient Matching Algorithm from the ODaT paper).
 *
 * This object is pure Kotlin with zero Corda and zero cryptography dependencies.
 * It operates exclusively on [DecryptedDonorData] / [DecryptedRecipientData] —
 * in-memory DTOs that exist only inside the MatchingAuthority's JVM.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * SECURITY NOTE
 * ─────────────────────────────────────────────────────────────────────────────
 * Input data arrives already decrypted — this engine never receives encrypted
 * ciphertexts and never calls AESUtils. Decryption is the responsibility of
 * OrganMatchingFlow (running on the MatchingAuthority node) BEFORE calling here.
 *
 * This separation guarantees that:
 *   (a) MatchingEngine can be unit-tested with plain Kotlin, no crypto setup.
 *   (b) The engine itself cannot accidentally leak decryption keys.
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Scoring weights:
 * ┌───────────────────────────────────┬────────┐
 * │ Criterion                         │ Points │
 * ├───────────────────────────────────┼────────┤
 * │ Location match (deceased donor)   │  +15   │
 * │ Confirmed paired donor (KPE)      │  +20   │
 * │ Size (BMI diff ≤ 5 kg/m²)        │  +15   │
 * │ Age compatible (diff ≤ 15 yrs)   │  +10   │
 * │ Condition score × 5 (urgency)    │ +5–50  │
 * │ Serial number tie-break           │ −0.001 │
 * └───────────────────────────────────┴────────┘
 *
 * Blood-type compatibility is a HARD filter applied before scoring.
 * Cross-match is checked AFTER scoring (most expensive step last).
 */
object MatchingEngine {

    // ── Weight constants (adjust here to tune allocation policy) ─────────────
    private const val W_LOCATION  = 15.0
    private const val W_PAIRED    = 20.0
    private const val W_SIZE      = 15.0
    private const val W_AGE       = 10.0
    private const val W_CONDITION = 5.0
    private const val W_SERIAL    = 0.001

    private const val BMI_TOLERANCE = 5.0
    private const val AGE_TOLERANCE = 15

    /**
     * Score record returned for each candidate recipient.
     * Holds the decrypted recipient DTO so the caller can access all fields
     * without a second decryption pass.
     */
    data class ScoredCandidate(
        val recipient: DecryptedRecipientData,
        val score: Double
    )

    // ── Single scoring source of truth ────────────────────────────────────────

    private fun computeScore(donor: DecryptedDonorData, r: DecryptedRecipientData): Double {
        var score = 0.0
        if (donor.isDeceased && donor.location.equals(r.location, ignoreCase = true)) score += W_LOCATION
        if (r.hasPairedDonor)                                                          score += W_PAIRED
        if (kotlin.math.abs(donor.bmi - r.bmi) <= BMI_TOLERANCE)                      score += W_SIZE
        if (kotlin.math.abs(donor.age - r.age) <= AGE_TOLERANCE)                       score += W_AGE
        score += r.conditionScore * W_CONDITION
        score -= r.serialNumber * W_SERIAL
        return score
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Find the best matching recipient for [donor] from [waitingRecipients].
     *
     * All data arrives pre-decrypted from the MatchingAuthority.
     * Prerequisites enforced by [OrganMatchingFlow] before calling here:
     *  - All recipients have status == WAITING
     *  - All recipients have organNeeded == donor.organType (filtered in-memory
     *    after decryption, since organNeeded is now an encrypted field)
     *
     * @return Best [ScoredCandidate] (recipient + score), or null if none qualify.
     */
    fun findBestMatch(
        donor: DecryptedDonorData,
        waitingRecipients: List<DecryptedRecipientData>,
        crossMatchFn: (DecryptedDonorData, DecryptedRecipientData) -> Boolean
    ): ScoredCandidate? {

        // Hard filter: blood-type compatibility
        val bloodCompatible = waitingRecipients.filter { r ->
            donor.bloodType.isCompatibleWith(r.bloodType)
        }
        if (bloodCompatible.isEmpty()) return null

        // Score + rank
        val scored = bloodCompatible
            .map { r -> ScoredCandidate(r, computeScore(donor, r)) }
            .sortedByDescending { it.score }

        // Cross-match: take first positive result
        return scored.firstOrNull { c -> crossMatchFn(donor, c.recipient) }
    }

    /**
     * Returns all scored candidates sorted by descending score — for audit/reporting.
     * Blood-type incompatible recipients are excluded.
     */
    fun scoreAll(
        donor: DecryptedDonorData,
        waitingRecipients: List<DecryptedRecipientData>
    ): List<ScoredCandidate> = waitingRecipients
        .filter { donor.bloodType.isCompatibleWith(it.bloodType) }
        .map { r -> ScoredCandidate(r, computeScore(donor, r)) }
        .sortedByDescending { it.score }
}
