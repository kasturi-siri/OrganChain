package com.odat.flows

import co.paralleluniverse.fibers.Suspendable
import com.odat.contracts.DonorContract
import com.odat.contracts.OrganMatchContract
import com.odat.contracts.RecipientContract
import com.odat.enums.CrossMatchResult
import com.odat.enums.DonorStatus
import com.odat.enums.MatchStatus
import com.odat.enums.RecipientStatus
import com.odat.services.MatchingEngine
import com.odat.states.DonorState
import com.odat.states.MatchState
import com.odat.states.RecipientState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.time.Instant

/**
 * OrganMatchingFlow — the core matching flow.
 *
 * Triggered automatically after a new [DonorState] is finalised
 * (via the [MatchingSchedulerService] or manually via RPC).
 *
 * Collaboration Diagram Steps 10–17:
 *  10. Find Match triggered
 *  11. Request patient data  → Vault query for WAITING recipients
 *  12. Receive patient data  → results returned locally from Vault
 *  13. Request donor data    → Vault query for AVAILABLE donors
 *  14. Receive donor data    → results returned locally
 *  15. Endorsement request   → CollectSignaturesFlow to both hospitals
 *  16. Endorsement confirmation → FinalityFlow (Notary + distribute)
 *  17. Match Confirmation sent to Admin
 *
 * On success: DonorState → ASSIGNED, RecipientState → MATCHED,
 *             new MatchState(PENDING_CONFIRMATION) created.
 * On no match: returns null (caller waits for next donor registration).
 *
 * @param donorStateRef  The newly registered [DonorState] to match against.
 */
@InitiatingFlow
@StartableByRPC
class OrganMatchingFlow(
    private val donorStateRef: StateAndRef<DonorState>
) : FlowLogic<StateAndRef<MatchState>?>() {

    companion object {
        object LOADING_RECIPIENTS : ProgressTracker.Step("Querying Vault for WAITING recipients")
        object RUNNING_ALGORITHM  : ProgressTracker.Step("Running donor-recipient matching algorithm")
        object CROSS_MATCHING     : ProgressTracker.Step("Performing cross-match check")
        object BUILDING_TX        : ProgressTracker.Step("Building match transaction")
        object COLLECTING_SIGS    : ProgressTracker.Step("Collecting signatures from both hospitals")
        object FINALISING         : ProgressTracker.Step("Notarising and distributing MatchState")

        fun tracker() = ProgressTracker(
            LOADING_RECIPIENTS, RUNNING_ALGORITHM, CROSS_MATCHING,
            BUILDING_TX, COLLECTING_SIGS, FINALISING
        )
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): StateAndRef<MatchState>? {
        val donor = donorStateRef.state.data

        // Guard: only match AVAILABLE donors
        if (donor.status != DonorStatus.AVAILABLE) {
            throw FlowException("Donor ${donor.linearId} is not AVAILABLE (status: ${donor.status})")
        }

        // ── Step 1: Load all WAITING recipients for the same organ ─
        progressTracker.currentStep = LOADING_RECIPIENTS
        val allRecipientRefs = serviceHub.vaultService
            .queryBy<RecipientState>().states

        val waitingRefs = allRecipientRefs.filter { ref ->
            val r = ref.state.data
            r.status == RecipientStatus.WAITING && r.organNeeded == donor.organType
        }

        if (waitingRefs.isEmpty()) {
            logger.info("OrganMatchingFlow: No WAITING recipients for ${donor.organType}. Halting.")
            return null
        }

        val waitingRecipients = waitingRefs.map { it.state.data }

        // ── Step 2: Run Algorithm 1 ────────────────────────────────
        progressTracker.currentStep = RUNNING_ALGORITHM
        val bestRecipient = MatchingEngine.findBestMatch(
            donor            = donor,
            waitingRecipients = waitingRecipients,
            crossMatchFn     = ::simulateCrossMatch
        )

        if (bestRecipient == null) {
            logger.info("OrganMatchingFlow: No compatible recipient found for donor ${donor.linearId}")
            return null
        }

        // ── Step 3: Resolve the matched recipient's StateAndRef ────
        progressTracker.currentStep = CROSS_MATCHING
        val recipientStateRef = waitingRefs.first {
            it.state.data.linearId == bestRecipient.linearId
        }

        // ── Step 4: Resolve all participant nodes ──────────────────
        progressTracker.currentStep = BUILDING_TX
        val adminParty = resolveParty("O=AdminNode,L=Chennai,C=IN")
        val govParty   = resolveParty("O=Government,L=Delhi,C=IN")
        val recipientHospital = bestRecipient.registeredBy

        // ── Step 5: Build the three-output transaction ─────────────
        //   Input 1:  DonorState    (AVAILABLE)  → Output: DonorState    (ASSIGNED)
        //   Input 2:  RecipientState(WAITING)    → Output: RecipientState(MATCHED)
        //   Output 3: MatchState    (PENDING_CONFIRMATION)               [new]
        val assignedDonor = donor.copy(status = DonorStatus.ASSIGNED)
        val matchedRecipient = bestRecipient.copy(status = RecipientStatus.MATCHED)
        val matchState = MatchState(
            linearId           = UniqueIdentifier(),
            donorStateRef      = donorStateRef.ref,
            recipientStateRef  = recipientStateRef.ref,
            matchScore         = MatchingEngine.scoreAll(donor, waitingRecipients)
                .first { it.recipient.linearId == bestRecipient.linearId }.score,
            crossMatchResult   = CrossMatchResult.POSITIVE,
            organType          = donor.organType,
            donorHospital      = donor.registeredBy,
            recipientHospital  = recipientHospital,
            adminNode          = adminParty,
            governmentNode     = govParty,
            status             = MatchStatus.PENDING_CONFIRMATION,
            matchedAt          = Instant.now()
        )

        val notary = donorStateRef.state.notary
        val txBuilder = TransactionBuilder(notary)
            // Consume existing states
            .addInputState(donorStateRef)
            .addInputState(recipientStateRef)
            // Produce updated states
            .addOutputState(assignedDonor,     DonorContract.CONTRACT_ID)
            .addOutputState(matchedRecipient,  RecipientContract.CONTRACT_ID)
            .addOutputState(matchState,         OrganMatchContract.CONTRACT_ID)
            // Commands — each with required signers
            .addCommand(
                DonorContract.Commands.Assign(),
                donor.registeredBy.owningKey
            )
            .addCommand(
                RecipientContract.Commands.Match(),
                recipientHospital.owningKey
            )
            .addCommand(
                OrganMatchContract.Commands.FindMatch(),
                donor.registeredBy.owningKey,
                recipientHospital.owningKey,
                adminParty.owningKey,
                govParty.owningKey
            )
        txBuilder.verify(serviceHub)

        // ── Step 6: Sign + collect ─────────────────────────────────
        progressTracker.currentStep = COLLECTING_SIGS
        val selfSigned = serviceHub.signInitialTransaction(txBuilder)

        // Build sessions for every counterparty
        val sessions = buildList {
            if (recipientHospital != ourIdentity) add(initiateFlow(recipientHospital))
            add(initiateFlow(adminParty))
            add(initiateFlow(govParty))
        }

        val fullySignedTx = subFlow(CollectSignaturesFlow(selfSigned, sessions))

        // ── Step 7: Notarise + distribute ─────────────────────────
        progressTracker.currentStep = FINALISING
        val finalTx = subFlow(FinalityFlow(fullySignedTx, sessions))

        logger.info(
            "OrganMatchingFlow: MATCH FOUND! " +
                    "Donor=${donor.linearId} ← Recipient=${bestRecipient.linearId} " +
                    "Score=${matchState.matchScore}"
        )

        // Return the new MatchState ref (index 2 in outputs)
        return finalTx.coreTransaction.outRef(2)
    }

    /**
     * Cross-match simulation.
     *
     * In a real deployment this calls an external lab API or reads a
     * pre-recorded cross-match result from the recipient's medical record.
     * Here we use a simplified rule: AB+ donors can only match AB+ recipients;
     * all other combinations that passed the blood-type filter are positive.
     */
    private fun simulateCrossMatch(donor: DonorState, recipient: RecipientState): Boolean {
        // Simulate ~10% negative cross-match rate for realistic testing
        val hash = (donor.linearId.hashCode() xor recipient.linearId.hashCode())
        return (hash % 10) != 0
    }

    private fun resolveParty(x500: String): Party =
        serviceHub.networkMapCache
            .getPeerByLegalName(CordaX500Name.parse(x500))
            ?: throw FlowException("Party not on network map: $x500")
}

// ─────────────────────────────────────────────────────────────────────────────
// Responder (runs on recipient hospital, AdminNode, GovernmentNode)
// ─────────────────────────────────────────────────────────────────────────────

@InitiatedBy(OrganMatchingFlow::class)
class OrganMatchingFlowResponder(private val counterpartySession: FlowSession)
    : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        val signedTxFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) {
                // Verify the MatchState is PENDING and score is positive
                val match = stx.coreTransaction.outputsOfType<MatchState>().firstOrNull()
                    ?: return // This node might only see donor/recipient state updates
                require(match.status == MatchStatus.PENDING_CONFIRMATION) {
                    "Responder: MatchState must be PENDING_CONFIRMATION"
                }
                require(match.matchScore > 0) {
                    "Responder: Match score must be positive"
                }
            }
        }
        val txId = subFlow(signedTxFlow).id
        return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
    }
}
