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

            is Commands.Register -> {
                requireThat {
                    "Register: no inputs allowed" using tx.inputs.isEmpty()
                    "Register: exactly one output required" using (tx.outputs.size == 1)

                    val out = tx.outputsOfType<RecipientState>().single()
                    "Register: status must be WAITING" using
                            (out.status == RecipientStatus.WAITING)
                    "Register: encrypted name must not be blank" using
                            out.encryptedName.isNotBlank()
                    "Register: age must be positive" using (out.age > 0)
                    "Register: conditionScore must be 1–10" using
                            (out.conditionScore in 1..10)
                    "Register: serialNumber must be positive" using
                            (out.serialNumber > 0)
                    "Register: registeredBy must sign" using
                            (command.signers.contains(out.registeredBy.owningKey))
                }
            }

            is Commands.Match -> {
                requireThat {
                    // FIXED: count only RecipientState instances, not all states.
                    // The matching transaction also contains a DonorState and a
                    // MatchState, so tx.inputs.size == 2 and tx.outputs.size == 3.
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
