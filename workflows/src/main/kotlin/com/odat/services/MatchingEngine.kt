package com.odat.services

import com.odat.states.DonorState
import com.odat.states.RecipientState

/**
 * MatchingEngine — stateless implementation of Algorithm 1
 * (Donor-Recipient Matching Algorithm from the ODaT paper).
 *
 * This object is pure Kotlin with zero Corda dependencies so it can be
 * unit-tested independently of the ledger.
 *
 * Scoring weights (tunable):
 * ┌───────────────────────────────────┬────────┐
 * │ Criterion                         │ Points │
 * ├───────────────────────────────────┼────────┤
 * │ Location match (deceased donor)   │  +15   │
 * │ Confirmed paired donor            │  +20   │
 * │ Size (BMI diff ≤ 5)              │  +15   │
 * │ Age compatible (diff ≤ 15 yrs)   │  +10   │
 * │ Condition score × 5 (urgency)    │ +5–50  │
 * │ Serial number tie-break           │ −0.001 │
 * └───────────────────────────────────┴────────┘
 *
 * Blood-type compatibility is a HARD filter applied before scoring.
 * Cross-match is checked AFTER scoring (most expensive step last).
 */
object MatchingEngine {

    // ── Weight constants (adjust here to tune allocation policy) ─
    private const val W_LOCATION    = 15.0
    private const val W_PAIRED      = 20.0
    private const val W_SIZE        = 15.0
    private const val W_AGE         = 10.0
    private const val W_CONDITION   = 5.0    // multiplied by conditionScore (1-10)
    private const val W_SERIAL      = 0.001  // subtracted × serialNumber

    // ── BMI / age tolerance ──────────────────────────────────────
    private const val BMI_TOLERANCE = 5.0    // kg/m²
    private const val AGE_TOLERANCE = 15     // years

    /**
     * Score record returned for each candidate recipient.
     *
     * @param recipient  The candidate [RecipientState]
     * @param score      Weighted compatibility score (higher = better)
     */
    data class ScoredCandidate(
        val recipient: RecipientState,
        val score: Double
    )

    /**
     * Find the best matching recipient for [donor] from [waitingRecipients].
     *
     * Prerequisites enforced by [OrganMatchingFlow] before calling here:
     *  - All recipients have status == WAITING
     *  - All recipients have organNeeded == donor.organType
     *
     * @param donor             The newly available donor
     * @param waitingRecipients All WAITING recipients needing the same organ
     * @param crossMatchFn      Lambda that performs the cross-match test
     *                          (returns true if compatible, false if not)
     * @return The best matching [RecipientState], or null if none qualify
     */
    fun findBestMatch(
        donor: DonorState,
        waitingRecipients: List<RecipientState>,
        crossMatchFn: (DonorState, RecipientState) -> Boolean
    ): RecipientState? {

        // ── Step 1: Hard filter — blood type compatibility ────────
        val bloodCompatible = waitingRecipients.filter { r ->
            donor.bloodType.isCompatibleWith(r.bloodType)
        }

        if (bloodCompatible.isEmpty()) return null

        // ── Step 2: Score each eligible recipient ─────────────────
        val scored: List<ScoredCandidate> = bloodCompatible.map { r ->
            var score = 0.0

            // Location bonus — only for deceased donors
            if (donor.isDeceased && donor.location.equals(r.location, ignoreCase = true)) {
                score += W_LOCATION
            }

            // Paired donor exchange bonus (KPE incentive)
            if (r.hasPairedDonor) score += W_PAIRED

            // Size compatibility — BMI difference within tolerance
            if (kotlin.math.abs(donor.bmi - r.bmi) <= BMI_TOLERANCE) {
                score += W_SIZE
            }

            // Age compatibility
            if (kotlin.math.abs(donor.age - r.age) <= AGE_TOLERANCE) {
                score += W_AGE
            }

            // Clinical urgency
            score += r.conditionScore * W_CONDITION

            // Waitlist order tie-break (earlier = slightly higher)
            score -= r.serialNumber * W_SERIAL

            ScoredCandidate(r, score)
        }.sortedByDescending { it.score }

        // ── Step 3: Cross-match — take first positive result ─────
        // (Algorithm 1: while crossMatch is negative → disqualify top & retry)
        return scored
            .firstOrNull { candidate -> crossMatchFn(donor, candidate.recipient) }
            ?.recipient
    }

    /**
     * Returns all scored candidates (unsorted) — useful for audit/reporting.
     */
    fun scoreAll(
        donor: DonorState,
        waitingRecipients: List<RecipientState>
    ): List<ScoredCandidate> = waitingRecipients
        .filter { donor.bloodType.isCompatibleWith(it.bloodType) }
        .map { r ->
            var score = 0.0
            if (donor.isDeceased && donor.location.equals(r.location, ignoreCase = true)) score += W_LOCATION
            if (r.hasPairedDonor)                                    score += W_PAIRED
            if (kotlin.math.abs(donor.bmi - r.bmi) <= BMI_TOLERANCE) score += W_SIZE
            if (kotlin.math.abs(donor.age - r.age) <= AGE_TOLERANCE)  score += W_AGE
            score += r.conditionScore * W_CONDITION
            score -= r.serialNumber * W_SERIAL
            ScoredCandidate(r, score)
        }
}
