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
 * Created by [OrganMatchingFlow] when the algorithm finds a positive cross-match.
 * Transitions: PENDING_CONFIRMATION → CONFIRMED (or REJECTED) via MatchConfirmationFlow.
 *
 * Participants: both hospitals + AdminNode + GovernmentNode
 */
@BelongsToContract(OrganMatchContract::class)
data class MatchState(

    val linearId: UniqueIdentifier = UniqueIdentifier(),

    /** StateRef of the consumed DonorState — provides full audit lineage. */
    val donorStateRef: StateRef,

    /** StateRef of the consumed RecipientState — full audit lineage. */
    val recipientStateRef: StateRef,

    /** Weighted score computed by Algorithm 1 (higher = better match). */
    val matchScore: Double,

    /** Result of the final immunological cross-match step. */
    val crossMatchResult: CrossMatchResult,

    /** Organ type — duplicated here for quick querying without resolving refs. */
    val organType: OrganType,

    // ── Network parties ───────────────────────────────────────────
    /** Hospital that registered the donor. */
    val donorHospital: Party,

    /** Hospital that registered the recipient. */
    val recipientHospital: Party,

    /** AdminNode — must countersign ConfirmMatch command. */
    val adminNode: Party,

    /** GovernmentNode — observer for regulatory audit. */
    val governmentNode: Party,

    // ── Status ────────────────────────────────────────────────────
    val status: MatchStatus = MatchStatus.PENDING_CONFIRMATION,

    val matchedAt: Instant = Instant.now(),

    /** Set when admin confirms or rejects the match. */
    val resolvedAt: Instant? = null,

    /** Optional rejection reason. */
    val rejectionReason: String? = null

) : ContractState {

    override val participants: List<AbstractParty>
        get() = listOf(donorHospital, recipientHospital, adminNode, governmentNode)
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * TransportState — records an organ transport assignment dispatched to the
 * TransporterNode after a match is confirmed.
 *
 * Participants: donorHospital + recipientHospital + TransporterNode + AdminNode
 */
@BelongsToContract(TransportContract::class)
data class TransportState(

    val linearId: UniqueIdentifier = UniqueIdentifier(),

    /** Reference to the confirmed MatchState. */
    val matchId: UniqueIdentifier,

    val organType: OrganType,

    val originHospital: Party,
    val destinationHospital: Party,
    val transporterNode: Party,
    val adminNode: Party,

    /**
     * Maximum number of hours the organ remains viable outside the body.
     * Heart: 4–6 h | Kidney: 24–36 h | Liver: 12–24 h | Lung: 4–6 h
     */
    val viabilityWindowHours: Int,

    val status: TransportStatus = TransportStatus.DISPATCHED,

    val dispatchTime: Instant = Instant.now(),

    val deliveredAt: Instant? = null

) : ContractState {

    override val participants: List<AbstractParty>
        get() = listOf(originHospital, destinationHospital, transporterNode, adminNode)
}
