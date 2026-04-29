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
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.time.Instant

/**
 * OrganMatchingFlow — the core matching flow.
 *
 * Triggered automatically after a new [DonorState] is finalised
 * (via the MatchingSchedulerService or manually via RPC).
 *
 * On success: DonorState → ASSIGNED, RecipientState → MATCHED,
 *             new MatchState(PENDING_CONFIRMATION) created.
 * On no match: returns null.
 *
 * FIXES applied:
 *   Finding #5  — Removed the second call to MatchingEngine.scoreAll() that
 *                 was used solely to recover the winner's score.  findBestMatch()
 *                 now returns ScoredCandidate? (recipient + score together), so the
 *                 score is already in hand — no second O(n) pass needed.
 *   Finding #7  — Removed the duplicate resolveParty() private helper; now uses
 *                 the shared extension function from FlowUtils.kt.
 *   Finding #13 — Removed explicit matchedAt = Instant.now() in MatchState
 *                 constructor; it is already the declared default.
 *   Finding #17 — Fixed misleading CROSS_MATCHING progress step: the step was set
 *                 *after* findBestMatch() returned (cross-match already done). Since
 *                 scoring and cross-matching both happen inside findBestMatch(), the
 *                 CROSS_MATCHING tracker step has been merged into RUNNING_ALGORITHM.
 *   Finding #18 — Corrected the stale comment in simulateCrossMatch() that
 *                 incorrectly described blood-type AB+ logic; the actual
 *                 implementation uses a hash-modulo simulation.
 *
 * @param donorStateRef  The newly registered [DonorState] to match against.
 */
@InitiatingFlow
@StartableByRPC
class OrganMatchingFlow(
    private val donorStateRef: StateAndRef<DonorState>
) : FlowLogic<StateAndRef<MatchState>?>() {

    companion object {
        // FIX #17: CROSS_MATCHING removed — blood-type filter, scoring, and
        //          cross-match all happen inside findBestMatch() during RUNNING_ALGORITHM.
        object LOADING_RECIPIENTS : ProgressTracker.Step("Querying Vault for WAITING recipients")
        object RUNNING_ALGORITHM  : ProgressTracker.Step("Running matching algorithm (score + cross-match)")
        object BUILDING_TX        : ProgressTracker.Step("Building match transaction")
        object COLLECTING_SIGS    : ProgressTracker.Step("Collecting signatures from both hospitals")
        object FINALISING         : ProgressTracker.Step("Notarising and distributing MatchState")

        fun tracker() = ProgressTracker(
            LOADING_RECIPIENTS, RUNNING_ALGORITHM, BUILDING_TX, COLLECTING_SIGS, FINALISING
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

        // ── Step 1: Load all WAITING recipients for the same organ ──────────
        progressTracker.currentStep = LOADING_RECIPIENTS
        val allRecipientRefs = serviceHub.vaultService.queryBy<RecipientState>().states

        val waitingRefs = allRecipientRefs.filter { ref ->
            val r = ref.state.data
            r.status == RecipientStatus.WAITING && r.organNeeded == donor.organType
        }

        if (waitingRefs.isEmpty()) {
            logger.info("OrganMatchingFlow: No WAITING recipients for ${donor.organType}. Halting.")
            return null
        }

        val waitingRecipients = waitingRefs.map { it.state.data }

        // ── Step 2: Run Algorithm 1 (blood-type filter → score → cross-match) ─
        // FIX #17: CROSS_MATCHING tracker step removed; all three sub-steps happen
        //          atomically inside findBestMatch() — set the tracker before the call.
        progressTracker.currentStep = RUNNING_ALGORITHM

        // FIX #5: findBestMatch() now returns ScoredCandidate? (recipient + score).
        //         The score is already computed — the former scoreAll() second pass is gone.
        val bestCandidate = MatchingEngine.findBestMatch(
            donor             = donor,
            waitingRecipients = waitingRecipients,
            crossMatchFn      = ::simulateCrossMatch
        )

        if (bestCandidate == null) {
            logger.info("OrganMatchingFlow: No compatible recipient found for donor ${donor.linearId}")
            return null
        }

        val bestRecipient = bestCandidate.recipient  // unwrap for clarity

        // ── Step 3: Resolve the matched recipient's StateAndRef ──────────────
        val recipientStateRef = waitingRefs.first {
            it.state.data.linearId == bestRecipient.linearId
        }

        // ── Step 4: Resolve all participant nodes ────────────────────────────
        progressTracker.currentStep = BUILDING_TX
        val adminParty        = ourIdentity
        val govParty          = resolveParty("O=Government,L=Delhi,C=IN")   // FIX #7: shared util
        val recipientHospital = bestRecipient.registeredBy

        // ── Step 5: Build the three-output transaction ───────────────────────
        //   Input 1:  DonorState    (AVAILABLE) → Output: DonorState    (ASSIGNED)
        //   Input 2:  RecipientState(WAITING)   → Output: RecipientState(MATCHED)
        //   Output 3: MatchState    (PENDING_CONFIRMATION)                [new]
        val assignedDonor    = donor.copy(status = DonorStatus.ASSIGNED)
        val matchedRecipient = bestRecipient.copy(status = RecipientStatus.MATCHED)
        val matchState = MatchState(
            linearId          = UniqueIdentifier(),
            donorStateRef     = donorStateRef.ref,
            recipientStateRef = recipientStateRef.ref,
            matchScore        = bestCandidate.score,   // FIX #5: score from ScoredCandidate — no second pass
            crossMatchResult  = CrossMatchResult.POSITIVE,
            organType         = donor.organType,
            donorHospital     = donor.registeredBy,
            recipientHospital = recipientHospital,
            adminNode         = adminParty,
            governmentNode    = govParty
            // FIX #13: status, matchedAt omitted — they are already declared defaults in MatchState
        )

        val notary    = donorStateRef.state.notary
        val txBuilder = TransactionBuilder(notary)
            .addInputState(donorStateRef)
            .addInputState(recipientStateRef)
            .addOutputState(assignedDonor,    DonorContract.CONTRACT_ID)
            .addOutputState(matchedRecipient, RecipientContract.CONTRACT_ID)
            .addOutputState(matchState,        OrganMatchContract.CONTRACT_ID)
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

        // ── Step 6: Sign + collect ───────────────────────────────────────────
        progressTracker.currentStep = COLLECTING_SIGS
        val selfSigned = serviceHub.signInitialTransaction(txBuilder)

        val sessions = buildList {
            if (recipientHospital != ourIdentity) add(initiateFlow(recipientHospital))
            add(initiateFlow(adminParty))
            add(initiateFlow(govParty))
        }
        val fullySignedTx = subFlow(CollectSignaturesFlow(selfSigned, sessions))

        // ── Step 7: Notarise + distribute ───────────────────────────────────
        progressTracker.currentStep = FINALISING
        val finalTx = subFlow(FinalityFlow(fullySignedTx, sessions))

        logger.info(
            "OrganMatchingFlow: MATCH FOUND! " +
                    "Donor=${donor.linearId} ← Recipient=${bestRecipient.linearId} " +
                    "Score=${bestCandidate.score}"
        )

        return finalTx.coreTransaction.outRef(2)   // MatchState is output index 2
    }

    /**
     * Cross-match simulation.
     *
     * FIX #18: Corrected stale comment. The original comment described an
     * "AB+ → AB+ only" blood-type rule that was never implemented. The actual
     * logic uses a deterministic hash to simulate a realistic ~10% negative
     * cross-match rate for development/demo purposes.
     *
     * In a real deployment this would call an external lab API or read a
     * pre-recorded cross-match result from the recipient's medical record.
     */
    private fun simulateCrossMatch(donor: DonorState, recipient: RecipientState): Boolean {
        // Deterministic hash of the (donor, recipient) pair — ~10% negatives for realism
        val hash = (donor.linearId.hashCode() xor recipient.linearId.hashCode())
        return (hash % 10) != 0
    }

    // FIX #7: resolveParty() private copy removed; the shared extension function
    //         from FlowUtils.kt is used instead (resolveParty(x500) call above).
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
                val match = stx.coreTransaction.outputsOfType<MatchState>().firstOrNull()
                    ?: return   // This node might only receive donor/recipient state updates
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
