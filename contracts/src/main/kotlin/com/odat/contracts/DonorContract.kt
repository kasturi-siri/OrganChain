package com.odat.contracts

import com.odat.enums.DonorStatus
import com.odat.states.DonorState
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction

/**
 * DonorContract — enforces valid state transitions for [DonorState].
 *
 * Commands:
 *  - [Register]  : Hospital registers a new donor  (no inputs → 1 output AVAILABLE)
 *  - [Assign]    : Organ assigned to a recipient   (1 AVAILABLE → 1 ASSIGNED)
 *  - [Expire]    : Organ viability elapsed          (1 AVAILABLE → 1 EXPIRED)
 */
class DonorContract : Contract {

    companion object {
        @JvmStatic
        val CONTRACT_ID = "com.odat.contracts.DonorContract"
    }

    // ── Commands ─────────────────────────────────────────────────
    interface Commands : CommandData {
        class Register : Commands
        class Assign   : Commands
        class Expire   : Commands
    }

    // ── Verification ─────────────────────────────────────────────
    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()

        when (command.value) {

            is Commands.Register -> {
                requireThat {
                    // Shape
                    "Register: no input states allowed" using tx.inputs.isEmpty()
                    "Register: exactly one output state required" using (tx.outputs.size == 1)

                    // Output state rules
                    val out = tx.outputsOfType<DonorState>().single()
                    "Register: status must be AVAILABLE" using
                            (out.status == DonorStatus.AVAILABLE)
                    "Register: donor name must not be empty" using
                            out.encryptedName.isNotBlank()
                    "Register: age must be positive" using (out.age > 0)
                    "Register: weight must be positive" using (out.weightKg > 0)
                    "Register: height must be positive" using (out.heightCm > 0)
                    "Register: location must not be empty" using
                            out.location.isNotBlank()

                    // Signature: registering hospital must sign
                    "Register: registeredBy party must sign" using
                            (command.signers.contains(out.registeredBy.owningKey))
                }
            }

            is Commands.Assign -> {
                requireThat {
                    "Assign: exactly one input required" using (tx.inputs.size == 1)
                    "Assign: exactly one output required" using (tx.outputs.size == 1)

                    val inp = tx.inputsOfType<DonorState>().single()
                    val out = tx.outputsOfType<DonorState>().single()

                    "Assign: input must be AVAILABLE" using
                            (inp.status == DonorStatus.AVAILABLE)
                    "Assign: output must be ASSIGNED" using
                            (out.status == DonorStatus.ASSIGNED)
                    "Assign: linearId must be unchanged" using
                            (inp.linearId == out.linearId)
                    "Assign: registering hospital must sign" using
                            (command.signers.contains(out.registeredBy.owningKey))
                }
            }

            is Commands.Expire -> {
                requireThat {
                    "Expire: exactly one input required" using (tx.inputs.size == 1)
                    "Expire: exactly one output required" using (tx.outputs.size == 1)

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
