package com.odat.flows

import co.paralleluniverse.fibers.Suspendable
import com.odat.contracts.DonorContract
import com.odat.services.AESUtils
import com.odat.services.KeyVaultService
import com.odat.states.DonorInput
import com.odat.states.DonorState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

/**
 * RegisterDonorFlow — registers a new organ donor on the Corda ledger.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * FULL-FIELD ENCRYPTION — what is encrypted and when
 * ─────────────────────────────────────────────────────────────────────────────
 * ALL personal and medical fields are encrypted before being written to
 * DonorState.  Two separate AES-256-GCM keys are used:
 *
 *   PII key (donor-specific):
 *     encryptedName    ← AESUtils.encrypt(input.name,    piiKey)
 *     encryptedContact ← AESUtils.encrypt(input.contact, piiKey)
 *
 *   Medical key (shared across nodes, decrypt authority: MatchingAuthority only):
 *     encryptedBloodType  ← AESUtils.encrypt(input.bloodType.name,        medKey)
 *     encryptedOrganType  ← AESUtils.encrypt(input.organType.name,        medKey)
 *     encryptedAge        ← AESUtils.encrypt(input.age.toString(),        medKey)
 *     encryptedWeightKg   ← AESUtils.encrypt(input.weightKg.toString(),   medKey)
 *     encryptedHeightCm   ← AESUtils.encrypt(input.heightCm.toString(),   medKey)
 *     encryptedIsDeceased ← AESUtils.encrypt(input.isDeceased.toString(), medKey)
 *     encryptedLocation   ← AESUtils.encrypt(input.location,              medKey)
 *
 * The MatchingAuthority is added as a DonorState participant so it receives
 * a vault copy and can run OrganMatchingFlow.
 *
 * Quasar / Java-17 note: all encryption is performed inside non-@Suspendable
 * helpers so SecretKey objects never appear in Quasar fiber checkpoints.
 */
@InitiatingFlow
@StartableByRPC
class RegisterDonorFlow(private val input: DonorInput) : FlowLogic<SignedTransaction>() {

    companion object {
        object VALIDATING  : ProgressTracker.Step("Validating donor input")
        object ENCRYPTING  : ProgressTracker.Step("Encrypting all fields with AES-256-GCM")
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

        // ── Step 1: Validate plaintext values BEFORE encryption ───────────────
        // These checks cannot be performed by the contract (fields are ciphertexts).
        progressTracker.currentStep = VALIDATING
        require(input.name.isNotBlank())     { "Donor name must not be blank" }
        require(input.contact.isNotBlank())  { "Donor contact must not be blank" }
        require(input.age > 0)               { "Donor age must be positive" }
        require(input.weightKg > 0)          { "Donor weight must be positive" }
        require(input.heightCm > 0)          { "Donor height must be positive" }
        require(input.location.isNotBlank()) { "Donor location must not be blank" }

        // ── Step 2: Encrypt ALL fields ────────────────────────────────────────
        // Both helpers are non-@Suspendable → SecretKey objects never enter Quasar
        // fiber checkpoints → no Kryo / Java-17 InaccessibleObjectException.
        progressTracker.currentStep = ENCRYPTING
        val (encName, encContact) = encryptDonorPii(input.name, input.contact)
        val encMedical            = encryptMedicalFields(input)

        // ── Step 3: Resolve counterparty nodes ────────────────────────────────
        // (Quasar checkpointing CAN start here — only Strings are in scope)
        val matchingAuthorityParty = resolveParty("O=MatchingAuthority,L=Chennai,C=IN")
        val adminParty             = resolveParty("O=AdminNode,L=Chennai,C=IN")
        val govParty               = resolveParty("O=Government,L=Delhi,C=IN")

        // ── Step 4: Build fully-encrypted DonorState ──────────────────────────
        progressTracker.currentStep = BUILDING
        val donorState = DonorState(
            linearId            = UniqueIdentifier(),
            encryptedName       = encName,
            encryptedContact    = encContact,
            encryptedBloodType  = encMedical.bloodType,
            encryptedOrganType  = encMedical.organType,
            encryptedAge        = encMedical.age,
            encryptedWeightKg   = encMedical.weightKg,
            encryptedHeightCm   = encMedical.heightCm,
            encryptedIsDeceased = encMedical.isDeceased,
            encryptedLocation   = encMedical.location,
            registeredBy        = ourIdentity,
            matchingAuthority   = matchingAuthorityParty,
            adminNode           = adminParty,
            governmentNode      = govParty
            // status = AVAILABLE, registrationTime = Instant.now() are defaults
        )

        val notary    = serviceHub.networkMapCache.notaryIdentities.first()
        val txBuilder = TransactionBuilder(notary)
            .addOutputState(donorState, DonorContract.CONTRACT_ID)
            .addCommand(
                DonorContract.Commands.Register(),
                ourIdentity.owningKey,
                matchingAuthorityParty.owningKey,
                adminParty.owningKey,
                govParty.owningKey
            )
        txBuilder.verify(serviceHub)

        // ── Step 5: Sign locally ──────────────────────────────────────────────
        progressTracker.currentStep = SIGNING
        val selfSigned = serviceHub.signInitialTransaction(txBuilder)

        // ── Step 6: Collect endorsement signatures ────────────────────────────
        progressTracker.currentStep = COLLECTING
        val maSession    = initiateFlow(matchingAuthorityParty)
        val adminSession = initiateFlow(adminParty)
        val govSession   = initiateFlow(govParty)
        val fullySignedTx = subFlow(
            CollectSignaturesFlow(selfSigned, listOf(maSession, adminSession, govSession))
        )

        // ── Step 7: Notarise + distribute ─────────────────────────────────────
        progressTracker.currentStep = FINALISING
        return subFlow(FinalityFlow(fullySignedTx, listOf(maSession, adminSession, govSession)))
    }

    // ── Non-@Suspendable encryption helpers ───────────────────────────────────
    // SecretKey objects are created, used, and garbage-collected entirely within
    // these call frames — they NEVER cross a Quasar checkpoint boundary.

    private fun encryptDonorPii(name: String, contact: String): Pair<String, String> {
        val key = serviceHub.cordaService(KeyVaultService::class.java).getDonorKey()
        return Pair(AESUtils.encrypt(name, key), AESUtils.encrypt(contact, key))
    }

    /**
     * Encrypts all medical matching fields using the shared medical key.
     * The MatchingAuthority holds the only authorised decrypt path for this key.
     */
    private fun encryptMedicalFields(d: DonorInput): EncryptedMedicalFields {
        val key = serviceHub.cordaService(KeyVaultService::class.java).getMedicalKey()
        return EncryptedMedicalFields(
            bloodType  = AESUtils.encrypt(d.bloodType.name,        key),
            organType  = AESUtils.encrypt(d.organType.name,        key),
            age        = AESUtils.encrypt(d.age.toString(),        key),
            weightKg   = AESUtils.encrypt(d.weightKg.toString(),   key),
            heightCm   = AESUtils.encrypt(d.heightCm.toString(),   key),
            isDeceased = AESUtils.encrypt(d.isDeceased.toString(), key),
            location   = AESUtils.encrypt(d.location,              key)
        )
    }

    /** Transient container for encrypted medical fields — avoids a 7-element tuple. */
    private data class EncryptedMedicalFields(
        val bloodType: String, val organType: String, val age: String,
        val weightKg: String, val heightCm: String,
        val isDeceased: String, val location: String
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Responder (runs on MatchingAuthority, AdminNode, GovernmentNode)
// ─────────────────────────────────────────────────────────────────────────────

@InitiatedBy(RegisterDonorFlow::class)
class RegisterDonorFlowResponder(private val counterpartySession: FlowSession)
    : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        val signedTxFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) {
                val donorState = stx.coreTransaction.outputsOfType<DonorState>().firstOrNull()
                    ?: throw FlowException("No DonorState found in transaction")
                // Verify encrypted fields are non-blank (cannot check plaintext values)
                require(donorState.encryptedName.isNotBlank()) {
                    "Responder: encryptedName must not be blank"
                }
                require(donorState.encryptedBloodType.isNotBlank()) {
                    "Responder: encryptedBloodType must not be blank"
                }
            }
        }
        val txId = subFlow(signedTxFlow).id
        return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
    }
}
