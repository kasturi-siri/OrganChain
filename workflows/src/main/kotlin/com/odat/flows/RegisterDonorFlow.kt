package com.odat.flows

import co.paralleluniverse.fibers.Suspendable
import com.odat.contracts.DonorContract
import com.odat.enums.BloodType
import com.odat.enums.DonorStatus
import com.odat.enums.OrganType
import com.odat.services.AESUtils
import com.odat.services.KeyVaultService
import com.odat.states.*
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.time.Instant


// ─────────────────────────────────────────────────────────────────────────────
// Data class for RPC input
// ─────────────────────────────────────────────────────────────────────────────



// ─────────────────────────────────────────────────────────────────────────────
// Initiating Flow (run on Hospital node)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RegisterDonorFlow — registers a new organ donor on the Corda ledger.
 *
 * Flow steps (Collaboration Diagram, Steps 7–9):
 *  1. Validate input fields
 *  2. Encrypt PII (name, contact) with AES-256-GCM
 *  3. Build DonorState + DonorContract.Commands.Register transaction
 *  4. Collect signatures from AdminNode + GovernmentNode (endorsement)
 *  5. Notarise and distribute final transaction (FinalityFlow)
 *
 * Signers: registering hospital + AdminNode + GovernmentNode
 */
@InitiatingFlow
@StartableByRPC
class RegisterDonorFlow(private val input: DonorInput) : FlowLogic<SignedTransaction>() {

    companion object {
        object VALIDATING  : ProgressTracker.Step("Validating donor input")
        object ENCRYPTING  : ProgressTracker.Step("Encrypting PII with AES-256-GCM")
        object BUILDING    : ProgressTracker.Step("Building transaction")
        object SIGNING     : ProgressTracker.Step("Signing transaction")
        object COLLECTING  : ProgressTracker.Step("Collecting endorsement signatures")
        object FINALISING  : ProgressTracker.Step("Notarising and distributing")

        fun tracker() = ProgressTracker(
            VALIDATING, ENCRYPTING, BUILDING, SIGNING, COLLECTING, FINALISING
        )
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {

        // ── Step 1: Validate ──────────────────────────────────────
        progressTracker.currentStep = VALIDATING
        require(input.name.isNotBlank())     { "Donor name must not be blank" }
        require(input.contact.isNotBlank())  { "Donor contact must not be blank" }
        require(input.age > 0)               { "Donor age must be positive" }
        require(input.weightKg > 0)          { "Donor weight must be positive" }
        require(input.heightCm > 0)          { "Donor height must be positive" }
        require(input.location.isNotBlank()) { "Donor location must not be blank" }

        // ── Step 2: Encrypt PII ───────────────────────────────────
        progressTracker.currentStep = ENCRYPTING
        val keyVault = serviceHub.cordaService(KeyVaultService::class.java)
        val aesKey   = keyVault.getDonorKey()
        val encName    = AESUtils.encrypt(input.name, aesKey)
        val encContact = AESUtils.encrypt(input.contact, aesKey)

        // ── Step 3: Resolve counterparty nodes ────────────────────
        val adminParty = resolveParty("O=AdminNode,L=Chennai,C=IN")
        val govParty   = resolveParty("O=Government,L=Delhi,C=IN")

        // ── Step 4: Build state + transaction ─────────────────────
        progressTracker.currentStep = BUILDING
        val donorState = DonorState(
            linearId       = UniqueIdentifier(),
            encryptedName  = encName,
            encryptedContact = encContact,
            bloodType      = input.bloodType,
            organType      = input.organType,
            age            = input.age,
            weightKg       = input.weightKg,
            heightCm       = input.heightCm,
            isDeceased     = input.isDeceased,
            location       = input.location,
            registeredBy   = ourIdentity,
            adminNode      = adminParty,
            governmentNode = govParty,
            status         = DonorStatus.AVAILABLE,
            registrationTime = Instant.now()
        )

        val notary    = serviceHub.networkMapCache.notaryIdentities.first()
        val txBuilder = TransactionBuilder(notary)
            .addOutputState(donorState, DonorContract.CONTRACT_ID)
            .addCommand(
                DonorContract.Commands.Register(),
                ourIdentity.owningKey,
                adminParty.owningKey,
                govParty.owningKey
            )
        txBuilder.verify(serviceHub)

        // ── Step 5: Sign locally ──────────────────────────────────
        progressTracker.currentStep = SIGNING
        val selfSigned = serviceHub.signInitialTransaction(txBuilder)

        // ── Step 6: Collect endorsement signatures ────────────────
        progressTracker.currentStep = COLLECTING
        val adminSession = initiateFlow(adminParty)
        val govSession   = initiateFlow(govParty)
        val fullySignedTx = subFlow(
            CollectSignaturesFlow(selfSigned, listOf(adminSession, govSession))
        )

        // ── Step 7: Notarise + distribute ─────────────────────────
        progressTracker.currentStep = FINALISING
        return subFlow(
            FinalityFlow(fullySignedTx, listOf(adminSession, govSession))
        )
    }

    /** Helper: resolve a party by X.500 name or throw a descriptive error. */
    private fun resolveParty(x500: String): Party =
        serviceHub.networkMapCache
            .getPeerByLegalName(CordaX500Name.parse(x500))
            ?: throw FlowException("Party not found on network map: $x500")
}

// ─────────────────────────────────────────────────────────────────────────────
// Responder (runs on AdminNode and GovernmentNode)
// ─────────────────────────────────────────────────────────────────────────────

@InitiatedBy(RegisterDonorFlow::class)
class RegisterDonorFlowResponder(private val counterpartySession: FlowSession)
    : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        // Check the transaction before signing (auto-validation of contract rules)
        val signedTxFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) {
                // Additional custom checks can go here
                // e.g., verify the registering party is a known hospital
                val donorState = stx.coreTransaction.outputsOfType<DonorState>().firstOrNull()
                    ?: throw FlowException("No DonorState found in transaction")
                require(donorState.status == DonorStatus.AVAILABLE) {
                    "Responder: DonorState must have AVAILABLE status"
                }
            }
        }
        val txId = subFlow(signedTxFlow).id
        return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
    }
}
