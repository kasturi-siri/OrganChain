package com.odat.flows

import co.paralleluniverse.fibers.Suspendable
import com.odat.contracts.OrganMatchContract
import com.odat.enums.MatchStatus
import com.odat.states.MatchState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.time.Instant

// ─────────────────────────────────────────────────────────────────────────────
// ConfirmMatchFlow  (Collaboration Diagram Step 17 — Match Confirmation)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ConfirmMatchFlow — Admin node transitions a PENDING_CONFIRMATION MatchState
 * to CONFIRMED, triggering downstream transport dispatch.
 *
 * Collaboration Diagram Steps 17–20:
 *  17. Match Confirmation → Admin
 *  18. Matching Notification → Government
 *  19. Update Patient (RecipientState already MATCHED from OrganMatchingFlow)
 *  20. Update Donor  (DonorState already ASSIGNED from OrganMatchingFlow)
 *
 * @param matchLinearId  The [UniqueIdentifier] of the MatchState to confirm.
 */
@InitiatingFlow
@StartableByRPC
class ConfirmMatchFlow(
    private val matchLinearId: UniqueIdentifier
) : FlowLogic<SignedTransaction>() {

    companion object {
        object FINDING   : ProgressTracker.Step("Locating MatchState in Vault")
        object BUILDING  : ProgressTracker.Step("Building confirmation transaction")
        object SIGNING   : ProgressTracker.Step("Signing and collecting signatures")
        object FINALISING: ProgressTracker.Step("Notarising and distributing")

        fun tracker() = ProgressTracker(FINDING, BUILDING, SIGNING, FINALISING)
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {

        // ── Locate the MatchState ──────────────────────────────────
        progressTracker.currentStep = FINDING
        val matchRef = findMatchState(matchLinearId)
        val match = matchRef.state.data

        require(match.status == MatchStatus.PENDING_CONFIRMATION) {
            "Match ${matchLinearId} is not PENDING_CONFIRMATION (current: ${match.status})"
        }
        require(match.adminNode == ourIdentity) {
            "Only the AdminNode may confirm a match"
        }

        // ── Build CONFIRMED output ─────────────────────────────────
        progressTracker.currentStep = BUILDING
        val confirmedMatch = match.copy(
            status     = MatchStatus.CONFIRMED,
            resolvedAt = Instant.now()
        )

        val notary = matchRef.state.notary
        val txBuilder = TransactionBuilder(notary)
            .addInputState(matchRef)
            .addOutputState(confirmedMatch, OrganMatchContract.CONTRACT_ID)
            .addCommand(
                OrganMatchContract.Commands.ConfirmMatch(),
                ourIdentity.owningKey,              // AdminNode
                match.donorHospital.owningKey,
                match.recipientHospital.owningKey,
                match.governmentNode.owningKey
            )
        txBuilder.verify(serviceHub)

        // ── Sign + collect ─────────────────────────────────────────
        progressTracker.currentStep = SIGNING
        val selfSigned = serviceHub.signInitialTransaction(txBuilder)

        val sessions = buildList {
            if (match.donorHospital != ourIdentity)     add(initiateFlow(match.donorHospital))
            if (match.recipientHospital != ourIdentity) add(initiateFlow(match.recipientHospital))
            if (match.governmentNode != ourIdentity)    add(initiateFlow(match.governmentNode))
        }

        val fullySignedTx = subFlow(CollectSignaturesFlow(selfSigned, sessions))

        // ── Finalise ───────────────────────────────────────────────
        progressTracker.currentStep = FINALISING
        val finalTx = subFlow(FinalityFlow(fullySignedTx, sessions))

        logger.info("ConfirmMatchFlow: Match ${matchLinearId} CONFIRMED")
        return finalTx
    }

    private fun findMatchState(id: UniqueIdentifier): StateAndRef<MatchState> =
        serviceHub.vaultService.queryBy<MatchState>().states
            .firstOrNull { it.state.data.linearId == id }
            ?: throw FlowException("MatchState not found in Vault: $id")
}

// ─────────────────────────────────────────────────────────────────────────────
// RejectMatchFlow
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RejectMatchFlow — Admin rejects a PENDING match (e.g., cross-match failure
 * confirmed by lab, donor family withdrawal, clinical re-assessment).
 *
 * After rejection the DonorState and RecipientState must be reinstated to
 * AVAILABLE / WAITING — handled by [ReinstateAfterRejectionFlow].
 *
 * @param matchLinearId  UniqueIdentifier of the MatchState to reject.
 * @param reason         Human-readable reason logged on the ledger.
 */
@InitiatingFlow
@StartableByRPC
class RejectMatchFlow(
    private val matchLinearId: UniqueIdentifier,
    private val reason: String
) : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        require(reason.isNotBlank()) { "Rejection reason must not be blank" }

        val matchRef = serviceHub.vaultService.queryBy<MatchState>().states
            .firstOrNull { it.state.data.linearId == matchLinearId }
            ?: throw FlowException("MatchState not found: $matchLinearId")

        val match = matchRef.state.data
        require(match.status == MatchStatus.PENDING_CONFIRMATION) {
            "Can only reject PENDING_CONFIRMATION matches"
        }
        require(match.adminNode == ourIdentity) {
            "Only the AdminNode may reject a match"
        }

        val rejectedMatch = match.copy(
            status          = MatchStatus.REJECTED,
            resolvedAt      = Instant.now(),
            rejectionReason = reason
        )

        val notary = matchRef.state.notary
        val txBuilder = TransactionBuilder(notary)
            .addInputState(matchRef)
            .addOutputState(rejectedMatch, OrganMatchContract.CONTRACT_ID)
            .addCommand(
                OrganMatchContract.Commands.RejectMatch(),
                ourIdentity.owningKey,
                match.donorHospital.owningKey,
                match.recipientHospital.owningKey
            )
        txBuilder.verify(serviceHub)

        val selfSigned = serviceHub.signInitialTransaction(txBuilder)
        val sessions = buildList {
            if (match.donorHospital != ourIdentity)     add(initiateFlow(match.donorHospital))
            if (match.recipientHospital != ourIdentity) add(initiateFlow(match.recipientHospital))
            if (match.governmentNode != ourIdentity)    add(initiateFlow(match.governmentNode))
        }
        val fullySignedTx = subFlow(CollectSignaturesFlow(selfSigned, sessions))
        return subFlow(FinalityFlow(fullySignedTx, sessions))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared Responder for both Confirm and Reject
// ─────────────────────────────────────────────────────────────────────────────

@InitiatedBy(ConfirmMatchFlow::class)
class ConfirmMatchFlowResponder(private val session: FlowSession)
    : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        val signFlow = object : SignTransactionFlow(session) {
            override fun checkTransaction(stx: SignedTransaction) {
                val match = stx.coreTransaction.outputsOfType<MatchState>().firstOrNull()
                    ?: throw FlowException("No MatchState in confirmation transaction")
                require(match.status in listOf(MatchStatus.CONFIRMED, MatchStatus.REJECTED)) {
                    "MatchState must be CONFIRMED or REJECTED"
                }
            }
        }
        val txId = subFlow(signFlow).id
        return subFlow(ReceiveFinalityFlow(session, expectedTxId = txId))
    }
}

@InitiatedBy(RejectMatchFlow::class)
class RejectMatchFlowResponder(private val session: FlowSession)
    : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        val signFlow = object : SignTransactionFlow(session) {
            override fun checkTransaction(stx: SignedTransaction) {
                val match = stx.coreTransaction.outputsOfType<MatchState>().singleOrNull()
                    ?: throw FlowException("No MatchState in reject transaction")
                require(!match.rejectionReason.isNullOrBlank()) {
                    "Rejection reason must be present"
                }
            }
        }
        val txId = subFlow(signFlow).id
        return subFlow(ReceiveFinalityFlow(session, expectedTxId = txId))
    }
}
