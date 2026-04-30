package com.odat.contracts

import com.odat.enums.DonorStatus
import com.odat.states.DonorState
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction

/**
 * DonorContract — enforces valid state transitions for [DonorState].
 *
 * Commands:
 *  - [Register] : Hospital registers a new donor  (no inputs → 1 AVAILABLE output)
 *  - [Assign]   : Organ assigned to a recipient   (AVAILABLE → ASSIGNED)
 *  - [Expire]   : Organ viability elapsed          (AVAILABLE → EXPIRED)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * VALIDATION CHANGES — full-field encryption redesign
 * ─────────────────────────────────────────────────────────────────────────────
 * Previous contract validated plaintext medical fields:
 *   "Register: age must be positive" using (out.age > 0)
 *   "Register: weight must be positive" using (out.weightKg > 0)
 *   etc.
 *
 * These checks are no longer possible because medical fields are now stored
 * as AES-256-GCM ciphertexts.  A contract running inside a deterministic
 * sandbox cannot hold decryption keys and cannot verify the plaintext values.
 *
 * New approach — two-layer validation:
 *
 *   Layer 1 (Flow, before encryption):
 *     require(input.age > 0) { "Donor age must be positive" }
 *     require(input.weightKg > 0) { ... }
 *     ... (all field checks remain in RegisterDonorFlow.call())
 *
 *   Layer 2 (Contract, after encryption):
 *     "Register: all encrypted fields must be non-blank"
 *     → confirms that the flow actually encrypted something (non-empty
 *       ciphertexts) and did not accidentally store an empty value.
 *
 * This preserves the contract's role as an immutable second-opinion validator
 * while respecting the encryption boundary.
 *
 * Status transitions (AVAILABLE → ASSIGNED / EXPIRED) and signer requirements
 * are unchanged — they operate on plaintext status and party keys.
 * ─────────────────────────────────────────────────────────────────────────────
 */
class DonorContract : Contract {

    companion object {
        @JvmStatic
        val CONTRACT_ID = "com.odat.contracts.DonorContract"
    }

    interface Commands : CommandData {
        class Register : Commands
        class Assign   : Commands
        class Expire   : Commands
    }

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()

        when (command.value) {

            // ── Register: 0 inputs → 1 AVAILABLE DonorState ───────────────────
            is Commands.Register -> {
                requireThat {
                    "Register: no input states allowed" using tx.inputs.isEmpty()
                    "Register: exactly one output state required" using (tx.outputs.size == 1)

                    val out = tx.outputsOfType<DonorState>().single()

                    "Register: status must be AVAILABLE" using
                            (out.status == DonorStatus.AVAILABLE)

                    // Encrypted field presence checks (Layer 2 validation).
                    // The flow has already validated the plaintext values before encryption.
                    "Register: encryptedName must not be blank" using
                            out.encryptedName.isNotBlank()
                    "Register: encryptedContact must not be blank" using
                            out.encryptedContact.isNotBlank()
                    "Register: encryptedBloodType must not be blank" using
                            out.encryptedBloodType.isNotBlank()
                    "Register: encryptedOrganType must not be blank" using
                            out.encryptedOrganType.isNotBlank()
                    "Register: encryptedAge must not be blank" using
                            out.encryptedAge.isNotBlank()
                    "Register: encryptedWeightKg must not be blank" using
                            out.encryptedWeightKg.isNotBlank()
                    "Register: encryptedHeightCm must not be blank" using
                            out.encryptedHeightCm.isNotBlank()
                    "Register: encryptedLocation must not be blank" using
                            out.encryptedLocation.isNotBlank()
                    "Register: encryptedIsDeceased must not be blank" using
                            out.encryptedIsDeceased.isNotBlank()

                    // Signer requirement unchanged
                    "Register: registeredBy party must sign" using
                            command.signers.contains(out.registeredBy.owningKey)
                }
            }

            // ── Assign: AVAILABLE → ASSIGNED (part of multi-state matching tx) ─
            is Commands.Assign -> {
                requireThat {
                    "Assign: exactly one DonorState input required" using
                            (tx.inputsOfType<DonorState>().size == 1)
                    "Assign: exactly one DonorState output required" using
                            (tx.outputsOfType<DonorState>().size == 1)

                    val inp = tx.inputsOfType<DonorState>().single()
                    val out = tx.outputsOfType<DonorState>().single()

                    "Assign: input must be AVAILABLE" using
                            (inp.status == DonorStatus.AVAILABLE)
                    "Assign: output must be ASSIGNED" using
                            (out.status == DonorStatus.ASSIGNED)
                    "Assign: linearId must be unchanged" using
                            (inp.linearId == out.linearId)
                    "Assign: registering hospital must sign" using
                            command.signers.contains(out.registeredBy.owningKey)
                }
            }

            // ── Expire: AVAILABLE → EXPIRED ───────────────────────────────────
            is Commands.Expire -> {
                requireThat {
                    "Expire: exactly one DonorState input required" using
                            (tx.inputsOfType<DonorState>().size == 1)
                    "Expire: exactly one DonorState output required" using
                            (tx.outputsOfType<DonorState>().size == 1)

                    val inp = tx.inputsOfType<DonorState>().single()
                    val out = tx.outputsOfType<DonorState>().single()

                    "Expire: input must be AVAILABLE" using
                            (inp.status == DonorStatus.AVAILABLE)
                    "Expire: output must be EXPIRED" using
                            (out.status == DonorStatus.EXPIRED)
                    "Expire: linearId must be unchanged" using
                            (inp.linearId == out.linearId)
                }
            }

            else -> throw IllegalArgumentException("Unknown DonorContract command.")
        }
    }
}
