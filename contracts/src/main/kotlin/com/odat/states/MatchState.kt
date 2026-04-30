package com.odat.states

import com.odat.contracts.OrganMatchContract
import com.odat.contracts.TransportContract
import com.odat.enums.CrossMatchResult
import com.odat.enums.MatchStatus
import com.odat.enums.OrganType
import com.odat.enums.TransportStatus
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import java.time.Instant

/**
 * MatchState — records an organ match between a donor and a recipient.
 *
 * Created by [OrganMatchingFlow] (running on the MatchingAuthority node)
 * when Algorithm 1 finds a positive cross-match.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHAT IS STORED IN PLAINTEXT vs. ENCRYPTED
 * ─────────────────────────────────────────────────────────────────────────────
 * Plaintext (operational metadata — needed for contract verification and
 * downstream flow decisions):
 *   - linearId, donorStateRef, recipientStateRef
 *   - matchScore        → ConfirmMatch contract checks score > 0
 *   - crossMatchResult  → FindMatch contract checks result == POSITIVE
 *   - organType         → TransportFlow uses this to set viabilityWindowHours
 *   - all Party references
 *   - status, matchedAt, resolvedAt, rejectionReason
 *
 * Not stored here:
 *   Patient personal/medical details (name, bloodType, age, etc.) remain
 *   encrypted inside DonorState / RecipientState and are only decrypted by the
 *   MatchingAuthority.  After confirmation, the MA sends a [MatchSummary] to
 *   each hospital via NotifyMatchedPartiesFlow (P2P, TLS-protected).
 *
 * Transitions:
 *   PENDING_CONFIRMATION → CONFIRMED (via ConfirmMatchFlow)
 *   PENDING_CONFIRMATION → REJECTED  (via RejectMatchFlow)
 *
 * Participants:
 *   donorHospital + recipientHospital + matchingAuthority + adminNode + governmentNode
 * ─────────────────────────────────────────────────────────────────────────────
 */
@BelongsToContract(OrganMatchContract::class)
data class MatchState(

    val linearId: UniqueIdentifier = UniqueIdentifier(),

    /** StateRef of the consumed DonorState — full audit lineage. */
    val donorStateRef: StateRef,

    /** StateRef of the consumed RecipientState — full audit lineage. */
    val recipientStateRef: StateRef,

    /** Weighted score computed by Algorithm 1 (higher = better match). */
    val matchScore: Double,

    /** Result of the immunological cross-match step. */
    val crossMatchResult: CrossMatchResult,

    /**
     * Organ type — stored plaintext so TransportFlow can determine viability
     * window without needing to decrypt the underlying states.
     */
    val organType: OrganType,

    // ── Network parties ────────────────────────────────────────────────────
    val donorHospital:     Party,
    val recipientHospital: Party,
    /** MatchingAuthority — created this match; must sign all transitions. */
    val matchingAuthority: Party,
    val adminNode:         Party,
    val governmentNode:    Party,

    // ── Status ─────────────────────────────────────────────────────────────
    val status: MatchStatus = MatchStatus.PENDING_CONFIRMATION,

    val matchedAt:   Instant  = Instant.now(),
    val resolvedAt:  Instant? = null,
    val rejectionReason: String? = null

) : ContractState {

    override val participants: List<AbstractParty>
        get() = listOf(donorHospital, recipientHospital, matchingAuthority, adminNode, governmentNode)
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * TransportState — records an organ transport assignment dispatched after
 * a match is confirmed.
 *
 * No changes to encryption model — transport metadata is operational and
 * does not contain patient PII.
 *
 * Participants: donorHospital + recipientHospital + transporterNode + adminNode
 */
@BelongsToContract(TransportContract::class)
data class TransportState(

    val linearId: UniqueIdentifier = UniqueIdentifier(),

    val matchId:     UniqueIdentifier,
    val organType:   OrganType,

    val originHospital:      Party,
    val destinationHospital: Party,
    val transporterNode:     Party,
    val adminNode:           Party,

    /**
     * Maximum hours the organ remains viable outside the body.
     * Heart/Lung: 4–6 h | Liver: 12–24 h | Kidney: 24–36 h
     */
    val viabilityWindowHours: Int,

    val status:      TransportStatus = TransportStatus.DISPATCHED,
    val dispatchTime: Instant        = Instant.now(),
    val deliveredAt:  Instant?       = null

) : ContractState {

    override val participants: List<AbstractParty>
        get() = listOf(originHospital, destinationHospital, transporterNode, adminNode)
}
