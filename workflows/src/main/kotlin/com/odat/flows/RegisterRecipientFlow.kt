package com.odat.flows

import co.paralleluniverse.fibers.Suspendable
import com.odat.contracts.RecipientContract
import com.odat.enums.BloodType
import com.odat.enums.OrganType
import com.odat.services.AESUtils
import com.odat.services.KeyVaultService
import com.odat.states.RecipientState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

// ─────────────────────────────────────────────────────────────────────────────
// Input DTO
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RecipientInput — single RPC argument for [RegisterRecipientFlow].
 * Contains plaintext values as entered by hospital staff.
 * All fields are encrypted inside the flow before ledger storage.
 */
@CordaSerializable
data class RecipientInput(
    val name:           String,
    val contact:        String,
    val bloodType:      BloodType,
    val organNeeded:    OrganType,
    val age:            Int,
    val weightKg:       Double,
    val heightCm:       Double,
    val conditionScore: Int,    // 1–10; 10 = most critical
    val serialNumber:   Int,    // waitlist registration order
    val hasPairedDonor: Boolean,
    val location:       String
)

// ─────────────────────────────────────────────────────────────────────────────
// Initiating Flow
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RegisterRecipientFlow — registers a patient onto the transplant waitlist.
 *
 * ALL personal and medical fields are AES-256-GCM encrypted before the
 * RecipientState is written to the ledger.  Two separate key pools:
 *
 *   PII key (recipient-specific):
 *     encryptedName, encryptedContact
 *
 *   Medical key (shared; MatchingAuthority holds decrypt authority):
 *     encryptedBloodType, encryptedOrganNeeded, encryptedAge, encryptedWeightKg,
 *     encryptedHeightCm, encryptedConditionScore, encryptedSerialNumber,
 *     encryptedHasPairedDonor, encryptedLocation
 *
 * The MatchingAuthority is added as a RecipientState participant so its vault
 * receives a copy and it can query all waiting recipients for matching.
 */
@InitiatingFlow
@StartableByRPC
class RegisterRecipientFlow(private val input: RecipientInput) : FlowLogic<SignedTransaction>() {

    companion object {
        object VALIDATING : ProgressTracker.Step("Validating recipient input")
        object ENCRYPTING : ProgressTracker.Step("Encrypting all fields with AES-256-GCM")
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

        // ── Step 1: Validate plaintext values BEFORE encryption ───────────────
        progressTracker.currentStep = VALIDATING
        require(input.name.isNotBlank())       { "Recipient name must not be blank" }
        require(input.contact.isNotBlank())    { "Recipient contact must not be blank" }
        require(input.age > 0)                 { "Age must be positive" }
        require(input.weightKg > 0)            { "Weight must be positive" }
        require(input.heightCm > 0)            { "Height must be positive" }
        require(input.conditionScore in 1..10) { "conditionScore must be 1–10" }
        require(input.serialNumber > 0)        { "serialNumber must be positive" }
        require(input.location.isNotBlank())   { "Location must not be blank" }

        // ── Step 2: Encrypt ALL fields ────────────────────────────────────────
        progressTracker.currentStep = ENCRYPTING
        val (encName, encContact) = encryptRecipientPii(input.name, input.contact)
        val encMedical            = encryptMedicalFields(input)

        // ── Step 3: Resolve counterparty nodes ────────────────────────────────
        val matchingAuthorityParty = resolveParty("O=MatchingAuthority,L=Chennai,C=IN")
        val adminParty             = resolveParty("O=AdminNode,L=Chennai,C=IN")
        val govParty               = resolveParty("O=Government,L=Delhi,C=IN")

        // ── Step 4: Build fully-encrypted RecipientState ──────────────────────
        progressTracker.currentStep = BUILDING
        val recipientState = RecipientState(
            linearId                = UniqueIdentifier(),
            encryptedName           = encName,
            encryptedContact        = encContact,
            encryptedBloodType      = encMedical.bloodType,
            encryptedOrganNeeded    = encMedical.organNeeded,
            encryptedAge            = encMedical.age,
            encryptedWeightKg       = encMedical.weightKg,
            encryptedHeightCm       = encMedical.heightCm,
            encryptedConditionScore = encMedical.conditionScore,
            encryptedSerialNumber   = encMedical.serialNumber,
            encryptedHasPairedDonor = encMedical.hasPairedDonor,
            encryptedLocation       = encMedical.location,
            registeredBy            = ourIdentity,
            matchingAuthority       = matchingAuthorityParty,
            adminNode               = adminParty,
            governmentNode          = govParty
            // status = WAITING, registrationTime = Instant.now() are defaults
        )

        val notary    = serviceHub.networkMapCache.notaryIdentities.first()
        val txBuilder = TransactionBuilder(notary)
            .addOutputState(recipientState, RecipientContract.CONTRACT_ID)
            .addCommand(
                RecipientContract.Commands.Register(),
                ourIdentity.owningKey,
                matchingAuthorityParty.owningKey,
                adminParty.owningKey,
                govParty.owningKey
            )
        txBuilder.verify(serviceHub)

        // ── Step 5: Sign + collect ─────────────────────────────────────────────
        progressTracker.currentStep = SIGNING
        val selfSigned = serviceHub.signInitialTransaction(txBuilder)

        progressTracker.currentStep = COLLECTING
        val maSession    = initiateFlow(matchingAuthorityParty)
        val adminSession = initiateFlow(adminParty)
        val govSession   = initiateFlow(govParty)
        val fullySignedTx = subFlow(
            CollectSignaturesFlow(selfSigned, listOf(maSession, adminSession, govSession))
        )

        // ── Step 6: Finalise ───────────────────────────────────────────────────
        progressTracker.currentStep = FINALISING
        return subFlow(FinalityFlow(fullySignedTx, listOf(maSession, adminSession, govSession)))
    }

    // ── Non-@Suspendable encryption helpers ───────────────────────────────────

    private fun encryptRecipientPii(name: String, contact: String): Pair<String, String> {
        val key = serviceHub.cordaService(KeyVaultService::class.java).getRecipientKey()
        return Pair(AESUtils.encrypt(name, key), AESUtils.encrypt(contact, key))
    }

    private fun encryptMedicalFields(r: RecipientInput): EncryptedMedicalFields {
        val key = serviceHub.cordaService(KeyVaultService::class.java).getMedicalKey()
        return EncryptedMedicalFields(
            bloodType      = AESUtils.encrypt(r.bloodType.name,          key),
            organNeeded    = AESUtils.encrypt(r.organNeeded.name,        key),
            age            = AESUtils.encrypt(r.age.toString(),          key),
            weightKg       = AESUtils.encrypt(r.weightKg.toString(),     key),
            heightCm       = AESUtils.encrypt(r.heightCm.toString(),     key),
            conditionScore = AESUtils.encrypt(r.conditionScore.toString(),key),
            serialNumber   = AESUtils.encrypt(r.serialNumber.toString(), key),
            hasPairedDonor = AESUtils.encrypt(r.hasPairedDonor.toString(),key),
            location       = AESUtils.encrypt(r.location,                key)
        )
    }

    private data class EncryptedMedicalFields(
        val bloodType: String, val organNeeded: String, val age: String,
        val weightKg: String, val heightCm: String, val conditionScore: String,
        val serialNumber: String, val hasPairedDonor: String, val location: String
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Responder (runs on MatchingAuthority, AdminNode, GovernmentNode)
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
                require(state.encryptedName.isNotBlank()) {
                    "Responder: encryptedName must not be blank"
                }
                require(state.encryptedOrganNeeded.isNotBlank()) {
                    "Responder: encryptedOrganNeeded must not be blank"
                }
            }
        }
        val txId = subFlow(signedTxFlow).id
        return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
    }
}
