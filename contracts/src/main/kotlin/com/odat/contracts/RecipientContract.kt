package com.odat.contracts

import com.odat.enums.RecipientStatus
import com.odat.states.RecipientState
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction

/**
 * RecipientContract — enforces valid state transitions for [RecipientState].
 *
 * Commands:
 *  - [Register]  : Hospital registers a new patient  (no inputs → 1 WAITING output)
 *  - [Match]     : Recipient has been matched        (WAITING → MATCHED)
 *  - [Complete]  : Transplant completed              (MATCHED → TRANSPLANTED)
 *  - [Remove]    : Patient removed from waitlist     (WAITING/MATCHED → REMOVED)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * VALIDATION CHANGES — full-field encryption redesign
 * ─────────────────────────────────────────────────────────────────────────────
 * Previous checks like "conditionScore must be 1–10" and "serialNumber must
 * be positive" operated on plaintext integers.  These values are now stored
 * as AES-256-GCM ciphertexts and cannot be evaluated by the contract.
 *
 * Those checks are preserved in the flow (Layer 1 — before encryption).
 * The contract performs Layer 2 validation: all encrypted blobs must be
 * non-blank, confirming that encryption actually ran.
 *
 * Status transitions and signer requirements are unchanged.
 * ─────────────────────────────────────────────────────────────────────────────
 */
class RecipientContract : Contract {

    companion object {
        @JvmStatic
        val CONTRACT_ID = "com.odat.contracts.RecipientContract"
    }

    interface Commands : CommandData {
        class Register  : Commands
        class Match     : Commands
        class Complete  : Commands
        class Remove    : Commands
    }

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()

        when (command.value) {

            // ── Register: 0 inputs → 1 WAITING RecipientState ────────────────
            is Commands.Register -> {
                requireThat {
                    "Register: no inputs allowed" using tx.inputs.isEmpty()
                    "Register: exactly one output required" using (tx.outputs.size == 1)

                    val out = tx.outputsOfType<RecipientState>().single()
                    "Register: status must be WAITING" using
                            (out.status == RecipientStatus.WAITING)

                    // Encrypted field presence checks (Layer 2 validation)
                    "Register: encryptedName must not be blank" using
                            out.encryptedName.isNotBlank()
                    "Register: encryptedContact must not be blank" using
                            out.encryptedContact.isNotBlank()
                    "Register: encryptedBloodType must not be blank" using
                            out.encryptedBloodType.isNotBlank()
                    "Register: encryptedOrganNeeded must not be blank" using
                            out.encryptedOrganNeeded.isNotBlank()
                    "Register: encryptedAge must not be blank" using
                            out.encryptedAge.isNotBlank()
                    "Register: encryptedConditionScore must not be blank" using
                            out.encryptedConditionScore.isNotBlank()
                    "Register: encryptedSerialNumber must not be blank" using
                            out.encryptedSerialNumber.isNotBlank()
                    "Register: encryptedLocation must not be blank" using
                            out.encryptedLocation.isNotBlank()

                    "Register: registeredBy must sign" using
                            command.signers.contains(out.registeredBy.owningKey)
                }
            }

            // ── Match: WAITING → MATCHED (multi-state matching tx) ────────────
            is Commands.Match -> {
                requireThat {
                    "Match: one RecipientState input required" using
                            (tx.inputsOfType<RecipientState>().size == 1)
                    "Match: one RecipientState output required" using
                            (tx.outputsOfType<RecipientState>().size == 1)

                    val inp = tx.inputsOfType<RecipientState>().single()
                    val out = tx.outputsOfType<RecipientState>().single()

                    "Match: input must be WAITING" using
                            (inp.status == RecipientStatus.WAITING)
                    "Match: output must be MATCHED" using
                            (out.status == RecipientStatus.MATCHED)
                    "Match: linearId must be unchanged" using
                            (inp.linearId == out.linearId)
                }
            }

            // ── Complete: MATCHED → TRANSPLANTED ─────────────────────────────
            is Commands.Complete -> {
                requireThat {
                    val inp = tx.inputsOfType<RecipientState>().single()
                    val out = tx.outputsOfType<RecipientState>().single()
                    "Complete: input must be MATCHED" using
                            (inp.status == RecipientStatus.MATCHED)
                    "Complete: output must be TRANSPLANTED" using
                            (out.status == RecipientStatus.TRANSPLANTED)
                }
            }

            // ── Remove: WAITING or MATCHED → REMOVED ─────────────────────────
            is Commands.Remove -> {
                requireThat {
                    val inp = tx.inputsOfType<RecipientState>().single()
                    "Remove: cannot remove TRANSPLANTED patient" using
                            (inp.status != RecipientStatus.TRANSPLANTED)
                }
            }

            else -> throw IllegalArgumentException("Unknown RecipientContract command.")
        }
    }
}
