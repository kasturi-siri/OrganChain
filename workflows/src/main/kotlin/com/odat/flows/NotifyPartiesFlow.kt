package com.odat.flows

import co.paralleluniverse.fibers.Suspendable
import com.odat.enums.BloodType
import com.odat.enums.MatchStatus
import com.odat.enums.OrganType
import com.odat.services.AESUtils
import com.odat.services.KeyVaultService
import com.odat.states.DecryptedDonorData
import com.odat.states.DecryptedRecipientData
import com.odat.states.DonorState
import com.odat.states.MatchState
import com.odat.states.MatchSummary
import com.odat.states.RecipientState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.utilities.unwrap

// ─────────────────────────────────────────────────────────────────────────────
// NotifyMatchedPartiesFlow
// ─────────────────────────────────────────────────────────────────────────────

/**
 * NotifyMatchedPartiesFlow — run by the MatchingAuthority after a match is
 * CONFIRMED. It:
 *
 *   1. Retrieves the MatchState from the vault.
 *   2. Loads the original (consumed) DonorState and RecipientState via their
 *      StateRefs from the validated transaction store.
 *   3. Decrypts all fields using the medical key (MA-only operation).
 *   4. Builds a [MatchSummary] and sends it to both hospitals over
 *      TLS-secured Corda P2P sessions.
 *
 * @param matchLinearId  The CONFIRMED MatchState to notify parties about.
 */
@InitiatingFlow
@StartableByRPC
class NotifyMatchedPartiesFlow(
    private val matchLinearId: UniqueIdentifier
) : FlowLogic<MatchSummary>() {

    @Suspendable
    override fun call(): MatchSummary {

        // ── Locate the confirmed MatchState ────────────────────────────────────
        val matchRef = serviceHub.vaultService.queryBy<MatchState>().states
            .firstOrNull { it.state.data.linearId == matchLinearId }
            ?: throw FlowException("MatchState $matchLinearId not found")

        val match = matchRef.state.data
        if (match.status != MatchStatus.CONFIRMED) {
            throw FlowException(
                "Can only notify parties for CONFIRMED matches (current: ${match.status})"
            )
        }

        // ── Retrieve and decrypt donor state ───────────────────────────────────
        // FIX: call each typed decrypt helper directly into an explicitly-typed
        // local variable — the compiler now knows the concrete type and can
        // resolve all field references when building MatchSummary.
        val donorTx = serviceHub.validatedTransactions
            .getTransaction(match.donorStateRef.txhash)
            ?: throw FlowException("Donor transaction ${match.donorStateRef.txhash} not found")
        val donorState = donorTx.coreTransaction
            .outputs[match.donorStateRef.index].data as DonorState

        val decryptedDonor: DecryptedDonorData = decryptDonorState(donorState)

        // ── Retrieve and decrypt recipient state ───────────────────────────────
        val recipientTx = serviceHub.validatedTransactions
            .getTransaction(match.recipientStateRef.txhash)
            ?: throw FlowException("Recipient transaction ${match.recipientStateRef.txhash} not found")
        val recipientState = recipientTx.coreTransaction
            .outputs[match.recipientStateRef.index].data as RecipientState

        val decryptedRecipient: DecryptedRecipientData = decryptRecipientState(recipientState)

        // ── Build summary ──────────────────────────────────────────────────────
        // Fields are now resolved against the concrete DecryptedDonorData /
        // DecryptedRecipientData types — no "Unresolved reference" errors.
        val summary = MatchSummary(
            donorName       = decryptedDonor.name,
            donorContact    = decryptedDonor.contact,
            donorBloodType  = decryptedDonor.bloodType.name,
            donorOrganType  = decryptedDonor.organType.name,
            donorLocation   = decryptedDonor.location,
            donorIsDeceased = decryptedDonor.isDeceased,

            recipientName      = decryptedRecipient.name,
            recipientContact   = decryptedRecipient.contact,
            recipientBloodType = decryptedRecipient.bloodType.name,
            recipientOrganType = decryptedRecipient.organNeeded.name,
            recipientLocation  = decryptedRecipient.location,
            recipientCondition = decryptedRecipient.conditionScore,

            matchScore    = match.matchScore,
            matchLinearId = match.linearId.toString()
        )

        // ── Send to donor hospital ─────────────────────────────────────────────
        if (match.donorHospital != ourIdentity) {
            val donorHospitalSession = initiateFlow(match.donorHospital)
            donorHospitalSession.send(summary)
        }

        // ── Send to recipient hospital ─────────────────────────────────────────
        if (match.recipientHospital != ourIdentity) {
            val recipientHospitalSession = initiateFlow(match.recipientHospital)
            recipientHospitalSession.send(summary)
        }

        logger.info(
            "NotifyMatchedPartiesFlow: MatchSummary dispatched to " +
                    "${match.donorHospital.name.organisation} and " +
                    "${match.recipientHospital.name.organisation} " +
                    "for match $matchLinearId"
        )

        return summary
    }

    // ── Non-@Suspendable decryption helpers ───────────────────────────────────
    // Both return their concrete data-class type so callers get full field access.
    // SecretKey objects live and die within these frames — never cross a Quasar
    // fiber checkpoint.

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

    private fun decryptRecipientState(s: RecipientState): DecryptedRecipientData {
        val medKey = serviceHub.cordaService(KeyVaultService::class.java).getMedicalKey()
        val piiKey = serviceHub.cordaService(KeyVaultService::class.java).getRecipientKey()
        return DecryptedRecipientData(
            linearId       = s.linearId,
            name           = AESUtils.decrypt(s.encryptedName,            piiKey),
            contact        = AESUtils.decrypt(s.encryptedContact,         piiKey),
            bloodType      = BloodType.valueOf(AESUtils.decrypt(s.encryptedBloodType,      medKey)),
            organNeeded    = OrganType.valueOf(AESUtils.decrypt(s.encryptedOrganNeeded,    medKey)),
            age            = AESUtils.decrypt(s.encryptedAge,             medKey).toInt(),
            weightKg       = AESUtils.decrypt(s.encryptedWeightKg,        medKey).toDouble(),
            heightCm       = AESUtils.decrypt(s.encryptedHeightCm,        medKey).toDouble(),
            conditionScore = AESUtils.decrypt(s.encryptedConditionScore,  medKey).toInt(),
            serialNumber   = AESUtils.decrypt(s.encryptedSerialNumber,    medKey).toInt(),
            hasPairedDonor = AESUtils.decrypt(s.encryptedHasPairedDonor,  medKey).toBoolean(),
            location       = AESUtils.decrypt(s.encryptedLocation,        medKey)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Responder — runs on donor hospital and recipient hospital
// ─────────────────────────────────────────────────────────────────────────────

@InitiatedBy(NotifyMatchedPartiesFlow::class)
class NotifyMatchedPartiesFlowResponder(private val session: FlowSession)
    : FlowLogic<MatchSummary>() {

    @Suspendable
    override fun call(): MatchSummary {
        val summary = session.receive<MatchSummary>().unwrap { it }
        logger.info(
            "NotifyMatchedPartiesFlowResponder: Received MatchSummary for " +
                    "match ${summary.matchLinearId} | " +
                    "Organ: ${summary.donorOrganType} | " +
                    "Recipient: ${summary.recipientName} | " +
                    "Score: ${summary.matchScore}"
        )
        return summary
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GetMatchSummaryFlow — on-demand RPC / REST access to the decrypted summary
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GetMatchSummaryFlow — decrypts and returns a [MatchSummary] via RPC without
 * pushing P2P notifications. Used by GET /api/match/summary/{matchLinearId}.
 *
 * Must be called on the MatchingAuthority node (holds the medical key).
 */
@InitiatingFlow
@StartableByRPC
class GetMatchSummaryFlow(
    private val matchLinearId: UniqueIdentifier
) : FlowLogic<MatchSummary>() {

    @Suspendable
    override fun call(): MatchSummary =
        subFlow(NotifyMatchedPartiesFlow(matchLinearId))
}