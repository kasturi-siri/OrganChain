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
 *
 * FIXES applied:
 *   Finding #4  — Scoring logic was copy-pasted between findBestMatch() and
 *                 scoreAll(), creating a DRY violation. Both now delegate to the
 *                 private computeScore() helper — one source of truth.
 *   Finding #5  — findBestMatch() now returns ScoredCandidate? instead of
 *                 RecipientState?, so callers get the pre-computed score without
 *                 a second O(n) pass through scoreAll().
 *   Finding #8  — scoreAll() now returns results sorted by descending score,
 *                 matching the contract implied by "audit/reporting" usage.
 */
object MatchingEngine {

    // ── Weight constants (adjust here to tune allocation policy) ─────────────
    private const val W_LOCATION    = 15.0
    private const val W_PAIRED      = 20.0
    private const val W_SIZE        = 15.0
    private const val W_AGE         = 10.0
    private const val W_CONDITION   = 5.0    // multiplied by conditionScore (1-10)
    private const val W_SERIAL      = 0.001  // subtracted × serialNumber

    // ── BMI / age tolerance ──────────────────────────────────────────────────
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

    // ── FIX #4: Single scoring source of truth ───────────────────────────────
    /**
     * Compute the weighted compatibility score between [donor] and [r].
     *
     * Called by both [findBestMatch] and [scoreAll] — one definition,
     * no duplication. Any change to weights or criteria is made here only.
     */
    private fun computeScore(donor: DonorState, r: RecipientState): Double {
        var score = 0.0

        // Location bonus — only for deceased donors
        if (donor.isDeceased && donor.location.equals(r.location, ignoreCase = true)) {
            score += W_LOCATION
        }
        // Paired donor exchange bonus (Kidney Paired Exchange incentive)
        if (r.hasPairedDonor) score += W_PAIRED

        // Size compatibility — BMI difference within tolerance
        if (kotlin.math.abs(donor.bmi - r.bmi) <= BMI_TOLERANCE) score += W_SIZE

        // Age compatibility
        if (kotlin.math.abs(donor.age - r.age) <= AGE_TOLERANCE) score += W_AGE

        // Clinical urgency (1–10 scale, contributes up to 50 points)
        score += r.conditionScore * W_CONDITION

        // Waitlist order tie-break (earlier serial = slightly higher score)
        score -= r.serialNumber * W_SERIAL

        return score
    }

    // ── FIX #5: Returns ScoredCandidate? instead of RecipientState? ──────────
    /**
     * Find the best matching recipient for [donor] from [waitingRecipients].
     *
     * Prerequisites enforced by [OrganMatchingFlow] before calling here:
     *  - All recipients have status == WAITING
     *  - All recipients have organNeeded == donor.organType
     *
     * @param donor              The newly available donor
     * @param waitingRecipients  All WAITING recipients needing the same organ
     * @param crossMatchFn       Lambda that performs the cross-match test
     *                           (returns true if compatible, false if not)
     * @return The best [ScoredCandidate] (recipient + score), or null if none qualify.
     *         The caller receives the pre-computed score — no second pass needed.
     */
    fun findBestMatch(
        donor: DonorState,
        waitingRecipients: List<RecipientState>,
        crossMatchFn: (DonorState, RecipientState) -> Boolean
    ): ScoredCandidate? {

        // ── Step 1: Hard filter — blood type compatibility ────────────────────
        val bloodCompatible = waitingRecipients.filter { r ->
            donor.bloodType.isCompatibleWith(r.bloodType)
        }
        if (bloodCompatible.isEmpty()) return null

        // ── Step 2: Score + rank using the shared computeScore helper ─────────
        val scored = bloodCompatible
            .map { r -> ScoredCandidate(r, computeScore(donor, r)) }
            .sortedByDescending { it.score }

        // ── Step 3: Cross-match — take the first positive result ──────────────
        // (Algorithm 1: while crossMatch is negative → disqualify top & retry)
        return scored.firstOrNull { candidate -> crossMatchFn(donor, candidate.recipient) }
    }

    // ── FIX #8: scoreAll now sorts results by descending score ────────────────
    /**
     * Returns all scored candidates sorted by descending score — for audit/reporting.
     *
     * Uses [computeScore] (same as [findBestMatch]) to guarantee consistency.
     * Results are sorted so the highest-scoring candidate appears first,
     * matching the ordering contract expected by audit consumers.
     */
    fun scoreAll(
        donor: DonorState,
        waitingRecipients: List<RecipientState>
    ): List<ScoredCandidate> = waitingRecipients
        .filter { donor.bloodType.isCompatibleWith(it.bloodType) }
        .map { r -> ScoredCandidate(r, computeScore(donor, r)) }
        .sortedByDescending { it.score }   // FIX #8: was unsorted
}
