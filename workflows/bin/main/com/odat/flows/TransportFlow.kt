package com.odat.flows

import co.paralleluniverse.fibers.Suspendable
import com.odat.contracts.TransportContract
import com.odat.enums.MatchStatus
import com.odat.enums.OrganType
import com.odat.enums.TransportStatus
import com.odat.states.MatchState
import com.odat.states.TransportState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.time.Instant

/**
 * Organ viability window (hours) per organ type.
 * Used to communicate urgency to the Transporter node.
 */
private val VIABILITY_HOURS = mapOf(
    OrganType.HEART           to 6,
    OrganType.LUNG            to 6,
    OrganType.LIVER           to 24,
    OrganType.KIDNEY          to 36,
    OrganType.PANCREAS        to 24,
    OrganType.CORNEA          to 168,    // 7 days
    OrganType.SMALL_INTESTINE to 12
)

// ─────────────────────────────────────────────────────────────────────────────
// DispatchTransportFlow
// ─────────────────────────────────────────────────────────────────────────────

/**
 * DispatchTransportFlow — creates a [TransportState] after a match is CONFIRMED,
 * assigning the organ transport to the TransporterNode.
 *
 * Typically initiated by the AdminNode immediately after [ConfirmMatchFlow].
 *
 * FIXES applied:
 *   Finding #7 — Removed the duplicate resolveParty() private helper.
 *                Now uses the shared extension function from FlowUtils.kt.
 *
 * @param matchLinearId  The confirmed [MatchState] to dispatch transport for.
 */
@InitiatingFlow
@StartableByRPC
class DispatchTransportFlow(
    private val matchLinearId: UniqueIdentifier
) : FlowLogic<SignedTransaction>() {

    companion object {
        object FINDING    : ProgressTracker.Step("Resolving confirmed MatchState")
        object BUILDING   : ProgressTracker.Step("Building TransportState")
        object COLLECTING : ProgressTracker.Step("Collecting signatures")
        object FINALISING : ProgressTracker.Step("Notarising and dispatching")

        fun tracker() = ProgressTracker(FINDING, BUILDING, COLLECTING, FINALISING)
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {

        // ── Locate confirmed MatchState ──────────────────────────────────────
        progressTracker.currentStep = FINDING
        val matchRef = serviceHub.vaultService.queryBy<MatchState>().states
            .firstOrNull { it.state.data.linearId == matchLinearId }
            ?: throw FlowException("MatchState $matchLinearId not found in Vault")

        val match = matchRef.state.data
        require(match.status == MatchStatus.CONFIRMED) {
            "Cannot dispatch transport — MatchState is not CONFIRMED (status: ${match.status})"
        }

        // ── Resolve TransporterNode ──────────────────────────────────────────
        val transporterParty = resolveParty("O=Transporter,L=Chennai,C=IN")   // FIX #7: shared util

        // ── Build TransportState ─────────────────────────────────────────────
        progressTracker.currentStep = BUILDING
        val viabilityHours = VIABILITY_HOURS[match.organType]
            ?: throw FlowException("Unknown organ type: ${match.organType}")

        val transportState = TransportState(
            linearId             = UniqueIdentifier(),
            matchId              = matchLinearId,
            organType            = match.organType,
            originHospital       = match.donorHospital,
            destinationHospital  = match.recipientHospital,
            transporterNode      = transporterParty,
            adminNode            = match.adminNode,
            viabilityWindowHours = viabilityHours,
            status               = TransportStatus.DISPATCHED,
            dispatchTime         = Instant.now()
        )

        val notary    = matchRef.state.notary
        val txBuilder = TransactionBuilder(notary)
            .addOutputState(transportState, TransportContract.CONTRACT_ID)
            .addCommand(
                TransportContract.Commands.Dispatch(),
                ourIdentity.owningKey,              // AdminNode (initiator)
                match.donorHospital.owningKey,
                transporterParty.owningKey
            )
        txBuilder.verify(serviceHub)

        // ── Sign + collect ───────────────────────────────────────────────────
        progressTracker.currentStep = COLLECTING
        val selfSigned = serviceHub.signInitialTransaction(txBuilder)

        val sessions = buildList {
            if (match.donorHospital     != ourIdentity) add(initiateFlow(match.donorHospital))
            if (transporterParty        != ourIdentity) add(initiateFlow(transporterParty))
            if (match.recipientHospital != ourIdentity) add(initiateFlow(match.recipientHospital))
            if (match.adminNode         != ourIdentity) add(initiateFlow(match.adminNode))
        }

        val fullySignedTx = subFlow(CollectSignaturesFlow(selfSigned, sessions))

        // ── Finalise ─────────────────────────────────────────────────────────
        progressTracker.currentStep = FINALISING
        val finalTx = subFlow(FinalityFlow(fullySignedTx, sessions))
        logger.info(
            "DispatchTransportFlow: Transport dispatched for match $matchLinearId | " +
                    "Viability: ${viabilityHours}h | Organ: ${match.organType}"
        )
        return finalTx
    }

    // FIX #7: resolveParty() private copy removed — shared FlowUtils extension used above.
}

// ─────────────────────────────────────────────────────────────────────────────
// UpdateTransportStatusFlow
// ─────────────────────────────────────────────────────────────────────────────

/**
 * UpdateTransportStatusFlow — Transporter node updates the organ transport status
 * (IN_TRANSIT → DELIVERED or FAILED).
 *
 * @param transportLinearId  The [TransportState] to update.
 * @param newStatus          Target [TransportStatus].
 */
@InitiatingFlow
@StartableByRPC
class UpdateTransportStatusFlow(
    private val transportLinearId: UniqueIdentifier,
    private val newStatus: TransportStatus
) : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        val transportRef = serviceHub.vaultService.queryBy<TransportState>().states
            .firstOrNull { it.state.data.linearId == transportLinearId }
            ?: throw FlowException("TransportState $transportLinearId not found")

        val transport = transportRef.state.data
        require(transport.transporterNode == ourIdentity) {
            "Only the assigned TransporterNode may update transport status"
        }
        require(transport.status != TransportStatus.DELIVERED) {
            "Cannot update a DELIVERED transport"
        }

        val updated = transport.copy(
            status      = newStatus,
            deliveredAt = if (newStatus == TransportStatus.DELIVERED) Instant.now() else null
        )

        val notary    = transportRef.state.notary
        val txBuilder = TransactionBuilder(notary)
            .addInputState(transportRef)
            .addOutputState(updated, TransportContract.CONTRACT_ID)
            .addCommand(
                TransportContract.Commands.UpdateStatus(),
                ourIdentity.owningKey
            )
        txBuilder.verify(serviceHub)

        val selfSigned    = serviceHub.signInitialTransaction(txBuilder)
        val adminSession  = initiateFlow(transport.adminNode)
        val originSession = if (transport.originHospital != ourIdentity)
            initiateFlow(transport.originHospital) else null
        val destSession   = if (transport.destinationHospital != ourIdentity)
            initiateFlow(transport.destinationHospital) else null

        val sessions      = listOfNotNull(adminSession, originSession, destSession)
        val fullySignedTx = subFlow(CollectSignaturesFlow(selfSigned, sessions))
        return subFlow(FinalityFlow(fullySignedTx, sessions))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Responders
// ─────────────────────────────────────────────────────────────────────────────

@InitiatedBy(DispatchTransportFlow::class)
class DispatchTransportFlowResponder(private val session: FlowSession)
    : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        val signFlow = object : SignTransactionFlow(session) {
            override fun checkTransaction(stx: SignedTransaction) {
                val ts = stx.coreTransaction.outputsOfType<TransportState>().firstOrNull()
                    ?: throw FlowException("No TransportState in dispatch transaction")
                require(ts.viabilityWindowHours > 0) { "Viability window must be positive" }
                require(ts.status == TransportStatus.DISPATCHED) { "Status must be DISPATCHED" }
            }
        }
        val txId = subFlow(signFlow).id
        return subFlow(ReceiveFinalityFlow(session, expectedTxId = txId))
    }
}

@InitiatedBy(UpdateTransportStatusFlow::class)
class UpdateTransportStatusFlowResponder(private val session: FlowSession)
    : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        val signFlow = object : SignTransactionFlow(session) {
            override fun checkTransaction(stx: SignedTransaction) { /* trust the contract */ }
        }
        val txId = subFlow(signFlow).id
        return subFlow(ReceiveFinalityFlow(session, expectedTxId = txId))
    }
}
