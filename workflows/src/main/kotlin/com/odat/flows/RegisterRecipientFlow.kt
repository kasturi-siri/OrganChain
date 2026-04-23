package com.odat.flows

import co.paralleluniverse.fibers.Suspendable
import com.odat.contracts.RecipientContract
import com.odat.enums.BloodType
import com.odat.enums.OrganType
import com.odat.enums.RecipientStatus
import com.odat.services.AESUtils
import com.odat.services.KeyVaultService
import com.odat.states.RecipientState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.time.Instant

// ─────────────────────────────────────────────────────────────────────────────
// Input DTO
// ─────────────────────────────────────────────────────────────────────────────

data class RecipientInput(
    val name: String,
    val contact: String,
    val bloodType: BloodType,
    val organNeeded: OrganType,
    val age: Int,
    val weightKg: Double,
    val heightCm: Double,
    /** Clinical urgency 1–10 (10 = critical). */
    val conditionScore: Int,
    /** Waitlist registration order number. */
    val serialNumber: Int,
    /** Has a paired incompatible donor — enables KPE bonus. */
    val hasPairedDonor: Boolean,
    val location: String
)

// ─────────────────────────────────────────────────────────────────────────────
// Initiating Flow
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RegisterRecipientFlow — registers a patient onto the transplant waitlist.
 *
 * Collaboration Diagram Steps 6, 8, 9:
 *  6. Hospital registers patient
 *  8. Endorsement request (CollectSignaturesFlow → Admin + Govt)
 *  9. Endorsement confirmation (FinalityFlow)
 */
@InitiatingFlow
@StartableByRPC
class RegisterRecipientFlow(private val input: RecipientInput) : FlowLogic<SignedTransaction>() {

    companion object {
        object VALIDATING : ProgressTracker.Step("Validating recipient input")
        object ENCRYPTING : ProgressTracker.Step("Encrypting PII with AES-256-GCM")
        object BUILDING   : ProgressTracker.Step("Building transaction")
        object SIGNING    : ProgressTracker.Step("Signing transaction")
        object COLLECTING : ProgressTracker.Step("Collecting endorsement signatures")
        object FINALISING : ProgressTracker.Step("Notarising and distributing")

        fun tracker() = ProgressTracker(
            VALIDATING, ENCRYPTING, BUILDING, SIGNING, COLLECTING, FINALISING
        )
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {

        // ── Validate ───────────────────────────────────────────────
        progressTracker.currentStep = VALIDATING
        require(input.name.isNotBlank())     { "Recipient name must not be blank" }
        require(input.contact.isNotBlank())  { "Recipient contact must not be blank" }
        require(input.age > 0)               { "Age must be positive" }
        require(input.weightKg > 0)          { "Weight must be positive" }
        require(input.heightCm > 0)          { "Height must be positive" }
        require(input.conditionScore in 1..10) { "conditionScore must be 1–10" }
        require(input.serialNumber > 0)      { "serialNumber must be positive" }
        require(input.location.isNotBlank()) { "Location must not be blank" }

        // ── Encrypt PII ────────────────────────────────────────────
        progressTracker.currentStep = ENCRYPTING
        val keyVault   = serviceHub.cordaService(KeyVaultService::class.java)
        val aesKey     = keyVault.getRecipientKey()
        val encName    = AESUtils.encrypt(input.name, aesKey)
        val encContact = AESUtils.encrypt(input.contact, aesKey)

        // ── Resolve parties ────────────────────────────────────────
        val adminParty = resolveParty("O=AdminNode,L=Chennai,C=IN")
        val govParty   = resolveParty("O=Government,L=Delhi,C=IN")

        // ── Build state ────────────────────────────────────────────
        progressTracker.currentStep = BUILDING
        val recipientState = RecipientState(
            linearId         = UniqueIdentifier(),
            encryptedName    = encName,
            encryptedContact = encContact,
            bloodType        = input.bloodType,
            organNeeded      = input.organNeeded,
            age              = input.age,
            weightKg         = input.weightKg,
            heightCm         = input.heightCm,
            conditionScore   = input.conditionScore,
            serialNumber     = input.serialNumber,
            hasPairedDonor   = input.hasPairedDonor,
            location         = input.location,
            registeredBy     = ourIdentity,
            adminNode        = adminParty,
            governmentNode   = govParty,
            status           = RecipientStatus.WAITING,
            registrationTime = Instant.now()
        )

        val notary    = serviceHub.networkMapCache.notaryIdentities.first()
        val txBuilder = TransactionBuilder(notary)
            .addOutputState(recipientState, RecipientContract.CONTRACT_ID)
            .addCommand(
                RecipientContract.Commands.Register(),
                ourIdentity.owningKey,
                adminParty.owningKey,
                govParty.owningKey
            )
        txBuilder.verify(serviceHub)

        // ── Sign + collect ─────────────────────────────────────────
        progressTracker.currentStep = SIGNING
        val selfSigned = serviceHub.signInitialTransaction(txBuilder)

        progressTracker.currentStep = COLLECTING
        val adminSession = initiateFlow(adminParty)
        val govSession   = initiateFlow(govParty)
        val fullySignedTx = subFlow(
            CollectSignaturesFlow(selfSigned, listOf(adminSession, govSession))
        )

        // ── Finalise ───────────────────────────────────────────────
        progressTracker.currentStep = FINALISING
        return subFlow(FinalityFlow(fullySignedTx, listOf(adminSession, govSession)))
    }

    private fun resolveParty(x500: String): Party =
        serviceHub.networkMapCache.getPeerByLegalName(CordaX500Name.parse(x500))
            ?: throw FlowException("Party not on network map: $x500")
}

// ─────────────────────────────────────────────────────────────────────────────
// Responder
// ─────────────────────────────────────────────────────────────────────────────

@InitiatedBy(RegisterRecipientFlow::class)
class RegisterRecipientFlowResponder(private val counterpartySession: FlowSession)
    : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        val signedTxFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) {
                val state = stx.coreTransaction.outputsOfType<RecipientState>().firstOrNull()
                    ?: throw FlowException("No RecipientState in transaction")
                require(state.conditionScore in 1..10) {
                    "Responder: conditionScore out of valid range"
                }
            }
        }
        val txId = subFlow(signedTxFlow).id
        return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
    }
}
