package com.odat.flows

import co.paralleluniverse.fibers.Suspendable
import com.odat.contracts.DonorContract
import com.odat.contracts.OrganMatchContract
import com.odat.contracts.RecipientContract
import com.odat.enums.BloodType
import com.odat.enums.CrossMatchResult
import com.odat.enums.DonorStatus
import com.odat.enums.MatchStatus
import com.odat.enums.OrganType
import com.odat.enums.RecipientStatus
import com.odat.services.AESUtils
import com.odat.services.KeyVaultService
import com.odat.services.MatchingEngine
import com.odat.states.DecryptedDonorData
import com.odat.states.DecryptedRecipientData
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

/**
 * OrganMatchingFlow — the core matching flow, running on the MatchingAuthority node.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * FULL ENCRYPTION LIFECYCLE
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *  Registration (Hospital node):
 *   plaintext → AES-256-GCM encrypt → DonorState / RecipientState (ciphertexts)
 *
 *  Matching (MatchingAuthority node — this flow):
 *   ciphertexts ─decrypt (medical key)─▶ DecryptedDonorData / DecryptedRecipientData
 *   decrypted DTOs ─▶ MatchingEngine.findBestMatch() ─▶ ScoredCandidate
 *   decrypted data is NEVER written back to the ledger
 *
 *  MatchState (created by this flow):
 *   Only operational metadata stored: matchScore, crossMatchResult, organType,
 *   party references, and StateRefs.  No patient data.
 *
 *  Notification (after confirmation — NotifyMatchedPartiesFlow):
 *   MatchingAuthority decrypts again → MatchSummary → TLS-secured P2P send
 *   to each hospital.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * @StartableByRPC: runs on the MatchingAuthority node's RPC interface.
 * @param donorStateRef The newly AVAILABLE DonorState to match against.
 */
@InitiatingFlow
@StartableByRPC
class OrganMatchingFlow(
    private val donorStateRef: StateAndRef<DonorState>
) : FlowLogic<StateAndRef<MatchState>?>() {

    companion object {
        object DECRYPTING_DONOR   : ProgressTracker.Step("Decrypting donor medical fields (MA key)")
        object LOADING_RECIPIENTS : ProgressTracker.Step("Querying vault for WAITING recipients")
        object DECRYPTING_RECIPS  : ProgressTracker.Step("Decrypting recipient medical fields (MA key)")
        object RUNNING_ALGORITHM  : ProgressTracker.Step("Running matching algorithm (score + cross-match)")
        object BUILDING_TX        : ProgressTracker.Step("Building match transaction")
        object COLLECTING_SIGS    : ProgressTracker.Step("Collecting signatures")
        object FINALISING         : ProgressTracker.Step("Notarising and distributing MatchState")

        fun tracker() = ProgressTracker(
            DECRYPTING_DONOR, LOADING_RECIPIENTS, DECRYPTING_RECIPS,
            RUNNING_ALGORITHM, BUILDING_TX, COLLECTING_SIGS, FINALISING
        )
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): StateAndRef<MatchState>? {
        val donorState = donorStateRef.state.data

        if (donorState.status != DonorStatus.AVAILABLE) {
            throw FlowException("Donor ${donorState.linearId} is not AVAILABLE (status: ${donorState.status})")
        }

        // ── Step 1: Decrypt the donor — MA-only operation ─────────────────────
        progressTracker.currentStep = DECRYPTING_DONOR
        val decryptedDonor = decryptDonorState(donorState)

        // ── Step 2: Load all WAITING recipients from vault ────────────────────
        progressTracker.currentStep = LOADING_RECIPIENTS
        val allRecipientRefs = serviceHub.vaultService.queryBy<RecipientState>().states
            .filter { it.state.data.status == RecipientStatus.WAITING }

        if (allRecipientRefs.isEmpty()) {
            logger.info("OrganMatchingFlow: No WAITING recipients. Halting.")
            return null
        }

        // ── Step 3: Decrypt all waiting recipients — MA-only operation ─────────
        // organNeeded is now encrypted, so organ-type filtering must happen AFTER
        // decryption (no DB-level predicate possible).
        progressTracker.currentStep = DECRYPTING_RECIPS
        val decryptedPairs = allRecipientRefs
            .map { ref -> Pair(ref, decryptRecipientState(ref.state.data)) }
            .filter { (_, dec) -> dec.organNeeded == decryptedDonor.organType }

        if (decryptedPairs.isEmpty()) {
            logger.info("OrganMatchingFlow: No WAITING recipients for ${decryptedDonor.organType}. Halting.")
            return null
        }

        val decryptedRecipients = decryptedPairs.map { it.second }

        // ── Step 4: Run Algorithm 1 on plaintext DTOs ─────────────────────────
        // MatchingEngine has NO access to keys or ciphertexts — pure algorithm.
        progressTracker.currentStep = RUNNING_ALGORITHM
        val bestCandidate = MatchingEngine.findBestMatch(
            donor             = decryptedDonor,
            waitingRecipients = decryptedRecipients,
            crossMatchFn      = ::simulateCrossMatch
        )

        if (bestCandidate == null) {
            logger.info("OrganMatchingFlow: No compatible recipient found for donor ${donorState.linearId}")
            return null
        }

        val bestDecryptedRecipient = bestCandidate.recipient
        val recipientStateRef      = decryptedPairs
            .first { it.second.linearId == bestDecryptedRecipient.linearId }
            .first

        // ── Step 5: Build the three-output transaction ────────────────────────
        //   Input 1:  DonorState     (AVAILABLE) → Output: DonorState     (ASSIGNED)
        //   Input 2:  RecipientState (WAITING)   → Output: RecipientState (MATCHED)
        //   Output 3: MatchState     (PENDING_CONFIRMATION)                [new]
        progressTracker.currentStep = BUILDING_TX

        val govParty          = resolveParty("O=Government,L=Delhi,C=IN")
        val recipientHospital = recipientStateRef.state.data.registeredBy

        val assignedDonor    = donorState.copy(status = DonorStatus.ASSIGNED)
        val matchedRecipient = recipientStateRef.state.data.copy(status = RecipientStatus.MATCHED)

        // MatchState stores only operational metadata — no patient PII or medical data
        val matchState = MatchState(
            linearId          = UniqueIdentifier(),
            donorStateRef     = donorStateRef.ref,
            recipientStateRef = recipientStateRef.ref,
            matchScore        = bestCandidate.score,
            crossMatchResult  = CrossMatchResult.POSITIVE,
            organType         = decryptedDonor.organType,  // operational metadata for TransportFlow
            donorHospital     = donorState.registeredBy,
            recipientHospital = recipientHospital,
            matchingAuthority = ourIdentity,
            adminNode         = donorState.adminNode,
            governmentNode    = govParty
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
                donorState.registeredBy.owningKey
            )
            .addCommand(
                RecipientContract.Commands.Match(),
                recipientHospital.owningKey
            )
            .addCommand(
                OrganMatchContract.Commands.FindMatch(),
                donorState.registeredBy.owningKey,
                recipientHospital.owningKey,
                ourIdentity.owningKey,          // matchingAuthority
                donorState.adminNode.owningKey,
                govParty.owningKey
            )
        txBuilder.verify(serviceHub)

        // ── Step 6: Sign + collect ─────────────────────────────────────────────
        progressTracker.currentStep = COLLECTING_SIGS
        val selfSigned = serviceHub.signInitialTransaction(txBuilder)

        val sessions = buildList {
            if (donorState.registeredBy != ourIdentity) add(initiateFlow(donorState.registeredBy))
            if (recipientHospital       != ourIdentity) add(initiateFlow(recipientHospital))
            add(initiateFlow(donorState.adminNode))
            add(initiateFlow(govParty))
        }
        val fullySignedTx = subFlow(CollectSignaturesFlow(selfSigned, sessions))

        // ── Step 7: Notarise + distribute ─────────────────────────────────────
        progressTracker.currentStep = FINALISING
        val finalTx = subFlow(FinalityFlow(fullySignedTx, sessions))

        logger.info(
            "OrganMatchingFlow: MATCH FOUND — " +
                    "Donor=${donorState.linearId} ← Recipient=${bestDecryptedRecipient.linearId} " +
                    "Organ=${decryptedDonor.organType} Score=${bestCandidate.score}"
        )

        return finalTx.coreTransaction.outRef(2)
    }

    // ── Non-@Suspendable decryption helpers — MA-only operations ─────────────
    // SecretKey stays within these call frames and is never captured by Quasar.

    /**
     * Decrypt all medical fields of a [DonorState] into a [DecryptedDonorData] DTO.
     * Runs on the MatchingAuthority node only.
     */
    private fun decryptDonorState(s: DonorState): DecryptedDonorData {
        val medKey = serviceHub.cordaService(KeyVaultService::class.java).getMedicalKey()
        val piiKey = serviceHub.cordaService(KeyVaultService::class.java).getDonorKey()
        return DecryptedDonorData(
            linearId   = s.linearId,
            name       = AESUtils.decrypt(s.encryptedName,       piiKey),
            contact    = AESUtils.decrypt(s.encryptedContact,    piiKey),
            bloodType  = BloodType.valueOf(AESUtils.decrypt(s.encryptedBloodType,  medKey)),
            organType  = OrganType.valueOf(AESUtils.decrypt(s.encryptedOrganType,  medKey)),
            age        = AESUtils.decrypt(s.encryptedAge,        medKey).toInt(),
            weightKg   = AESUtils.decrypt(s.encryptedWeightKg,   medKey).toDouble(),
            heightCm   = AESUtils.decrypt(s.encryptedHeightCm,   medKey).toDouble(),
            isDeceased = AESUtils.decrypt(s.encryptedIsDeceased, medKey).toBoolean(),
            location   = AESUtils.decrypt(s.encryptedLocation,   medKey)
        )
    }

    /**
     * Decrypt all medical fields of a [RecipientState] into a [DecryptedRecipientData] DTO.
     * Runs on the MatchingAuthority node only.
     */
    private fun decryptRecipientState(s: RecipientState): DecryptedRecipientData {
        val medKey = serviceHub.cordaService(KeyVaultService::class.java).getMedicalKey()
        val piiKey = serviceHub.cordaService(KeyVaultService::class.java).getRecipientKey()
        return DecryptedRecipientData(
            linearId       = s.linearId,
            name           = AESUtils.decrypt(s.encryptedName,           piiKey),
            contact        = AESUtils.decrypt(s.encryptedContact,        piiKey),
            bloodType      = BloodType.valueOf(AESUtils.decrypt(s.encryptedBloodType,      medKey)),
            organNeeded    = OrganType.valueOf(AESUtils.decrypt(s.encryptedOrganNeeded,    medKey)),
            age            = AESUtils.decrypt(s.encryptedAge,            medKey).toInt(),
            weightKg       = AESUtils.decrypt(s.encryptedWeightKg,       medKey).toDouble(),
            heightCm       = AESUtils.decrypt(s.encryptedHeightCm,       medKey).toDouble(),
            conditionScore = AESUtils.decrypt(s.encryptedConditionScore, medKey).toInt(),
            serialNumber   = AESUtils.decrypt(s.encryptedSerialNumber,   medKey).toInt(),
            hasPairedDonor = AESUtils.decrypt(s.encryptedHasPairedDonor, medKey).toBoolean(),
            location       = AESUtils.decrypt(s.encryptedLocation,       medKey)
        )
    }

    /**
     * Cross-match simulation (deterministic ~10% negative rate).
     * In production: call external lab API or read pre-recorded result.
     */
    private fun simulateCrossMatch(
        donor: DecryptedDonorData,
        recipient: DecryptedRecipientData
    ): Boolean {
        val hash = (donor.linearId.hashCode() xor recipient.linearId.hashCode())
        return (hash % 10) != 0
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Responder (runs on donor hospital, recipient hospital, AdminNode, GovernmentNode)
// ─────────────────────────────────────────────────────────────────────────────

@InitiatedBy(OrganMatchingFlow::class)
class OrganMatchingFlowResponder(private val counterpartySession: FlowSession)
    : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        val signedTxFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) {
                val match = stx.coreTransaction.outputsOfType<MatchState>().firstOrNull()
                    ?: return  // Node sees only donor/recipient state updates
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
