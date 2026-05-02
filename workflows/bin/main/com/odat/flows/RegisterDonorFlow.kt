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
 * Key design note — Kryo / Java-17 module-access:
 *   Encryption is delegated to the non-@Suspendable helper [encryptDonorPii].
 *   Quasar only instruments @Suspendable methods for fiber checkpointing.
 *   SecretKey is created, used, and goes out of scope entirely within that
 *   helper frame — Kryo never sees it, preventing the InaccessibleObjectException
 *   that occurs when SecretKeySpec.key is accessed under Java 17's module system.
 *
 * FIXES applied:
 *   Finding #7  — Removed the duplicate resolveParty() private helper.
 *                 Now uses the shared extension function from FlowUtils.kt.
 *   Finding #11 — Removed explicitly-set DonorState defaults:
 *                   status           = DonorStatus.AVAILABLE   (default in DonorState)
 *                   registrationTime = Instant.now()           (default in DonorState)
 *                 Setting declared defaults explicitly is misleading — it implies the
 *                 defaults might differ from the values being passed.
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

        // ── Step 1: Validate ──────────────────────────────────────────────────
        progressTracker.currentStep = VALIDATING
        require(input.name.isNotBlank())     { "Donor name must not be blank" }
        require(input.contact.isNotBlank())  { "Donor contact must not be blank" }
        require(input.age > 0)               { "Donor age must be positive" }
        require(input.weightKg > 0)          { "Donor weight must be positive" }
        require(input.heightCm > 0)          { "Donor height must be positive" }
        require(input.location.isNotBlank()) { "Donor location must not be blank" }

        // ── Step 2: Encrypt PII ───────────────────────────────────────────────
        // SecretKey stays inside encryptDonorPii() (non-@Suspendable) and is
        // garbage-collected before any Quasar checkpoint can occur.
        progressTracker.currentStep = ENCRYPTING
        val (encName, encContact) = encryptDonorPii(input.name, input.contact)

        // ── Step 3: Resolve counterparty nodes ────────────────────────────────
        val adminParty = resolveParty("O=AdminNode,L=Chennai,C=IN")   // FIX #7: shared util
        val govParty   = resolveParty("O=Government,L=Delhi,C=IN")    // FIX #7: shared util

        // ── Step 4: Build state + transaction ─────────────────────────────────
        progressTracker.currentStep = BUILDING
        val donorState = DonorState(
            linearId         = UniqueIdentifier(),
            encryptedName    = encName,
            encryptedContact = encContact,
            bloodType        = input.bloodType,
            organType        = input.organType,
            age              = input.age,
            weightKg         = input.weightKg,
            heightCm         = input.heightCm,
            isDeceased       = input.isDeceased,
            location         = input.location,
            registeredBy     = ourIdentity,
            adminNode        = adminParty,
            governmentNode   = govParty
            // FIX #11: status and registrationTime omitted — they are already
            //          declared defaults (AVAILABLE, Instant.now()) in DonorState.
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

        // ── Step 5: Sign locally ──────────────────────────────────────────────
        progressTracker.currentStep = SIGNING
        val selfSigned = serviceHub.signInitialTransaction(txBuilder)

        // ── Step 6: Collect endorsement signatures ────────────────────────────
        progressTracker.currentStep = COLLECTING
        val adminSession = initiateFlow(adminParty)
        val govSession   = initiateFlow(govParty)
        val fullySignedTx = subFlow(
            CollectSignaturesFlow(selfSigned, listOf(adminSession, govSession))
        )

        // ── Step 7: Notarise + distribute ─────────────────────────────────────
        progressTracker.currentStep = FINALISING
        return subFlow(FinalityFlow(fullySignedTx, listOf(adminSession, govSession)))
    }

    /**
     * NON-@Suspendable encryption helper.
     *
     * Quasar does NOT instrument this method — it CANNOT checkpoint inside it —
     * so Kryo NEVER sees the SecretKey. Returns (encryptedName, encryptedContact).
     */
    private fun encryptDonorPii(name: String, contact: String): Pair<String, String> {
        val keyVault = serviceHub.cordaService(KeyVaultService::class.java)
        val key      = keyVault.getDonorKey()
        return Pair(
            AESUtils.encrypt(name,    key),
            AESUtils.encrypt(contact, key)
        )
    }

    // FIX #7: resolveParty() private copy removed — shared FlowUtils extension used above.
}

// ─────────────────────────────────────────────────────────────────────────────
// Responder (runs on AdminNode and GovernmentNode)
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
                require(donorState.encryptedName.isNotBlank()) {
                    "Responder: DonorState encryptedName must not be blank"
                }
            }
        }
        val txId = subFlow(signedTxFlow).id
        return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
    }
}
