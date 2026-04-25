package com.odat.flows

import co.paralleluniverse.fibers.Suspendable
import com.odat.contracts.DonorContract
import com.odat.enums.DonorStatus
import com.odat.services.AESUtils
import com.odat.services.KeyVaultService
import com.odat.states.DonorInput
import com.odat.states.DonorState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.time.Instant

/**
 * RegisterDonorFlow — registers a new organ donor on the Corda ledger.
 *
 * ═══════════════════════════════════════════════════════════════════
 * BUG FIX — Kryo / Java-17 module-access crash (CRITICAL)
 * ═══════════════════════════════════════════════════════════════════
 * SYMPTOM:
 *   POST /api/donor/register returns:
 *   "KryoException: InaccessibleObjectException: Unable to make field
 *    private byte[] javax.crypto.spec.SecretKeySpec.key accessible:
 *    module java.base does not 'opens javax.crypto.spec' to unnamed module"
 *
 * ROOT CAUSE:
 *   Corda uses Quasar fibers for flow execution. At every @Suspendable
 *   call point (initiateFlow, subFlow …), Quasar serializes the entire
 *   fiber call-stack via Kryo so the flow can be checkpointed to disk
 *   and resumed after a node restart.
 *
 *   The original code declared:
 *       val aesKey = keyVault.getDonorKey()   // SecretKeySpec
 *   as a local variable inside the @Suspendable call() method.
 *   When Quasar checkpointed at the first initiateFlow(), Kryo tried
 *   to serialize SecretKeySpec — which has a private byte[] field in
 *   the java.base module. Java 17's strong module encapsulation blocks
 *   the reflective access Kryo needs, causing the crash.
 *
 * FIX — extract encryption into a NON-@Suspendable private helper:
 *   Quasar ONLY instruments and checkpoints @Suspendable methods.
 *   A plain (non-annotated) private method is treated as a normal
 *   JVM call. SecretKey is created, used, and leaves scope entirely
 *   within encryptDonorPii() — it is NEVER part of the fiber snapshot.
 *   The only value that crosses into the @Suspendable call() is a
 *   Pair<String, String> (two Base64 ciphertext strings), which Kryo
 *   serializes with no difficulty.
 *
 *   Stack at checkpoint — BEFORE fix:
 *       call() locals: aesKey=SecretKeySpec ← Kryo cannot access
 *
 *   Stack at checkpoint — AFTER fix:
 *       call() locals: encName=String, encContact=String ← OK
 * ═══════════════════════════════════════════════════════════════════
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
        // FIX: delegate to non-@Suspendable helper.
        // SecretKey lives only inside encryptDonorPii() and is
        // garbage-collected before any checkpoint occurs.
        progressTracker.currentStep = ENCRYPTING
        val (encName, encContact) = encryptDonorPii(input.name, input.contact)

        // ── Step 3: Resolve counterparty nodes ────────────────────
        // Quasar checkpointing CAN start here.
        // At this point the fiber stack only holds Strings — safe.
        val adminParty = resolveParty("O=AdminNode,L=Chennai,C=IN")
        val govParty   = resolveParty("O=Government,L=Delhi,C=IN")

        // ── Step 4: Build state + transaction ─────────────────────
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
            governmentNode   = govParty,
            status           = DonorStatus.AVAILABLE,
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
        return subFlow(FinalityFlow(fullySignedTx, listOf(adminSession, govSession)))
    }

    /**
     * NON-@Suspendable encryption helper.
     *
     * Quasar does NOT instrument this method → it CANNOT checkpoint
     * inside it → Kryo NEVER sees the SecretKey.
     *
     * The SecretKey is created, used, and goes out of scope entirely
     * within this call frame. The calling @Suspendable method only
     * ever sees the resulting Pair<String, String>.
     */
    private fun encryptDonorPii(name: String, contact: String): Pair<String, String> {
        val keyVault = serviceHub.cordaService(KeyVaultService::class.java)
        val key      = keyVault.getDonorKey()     // SecretKeySpec — stays here ONLY
        return Pair(
            AESUtils.encrypt(name,    key),
            AESUtils.encrypt(contact, key)
        )
    }

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
        val signedTxFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) {
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
