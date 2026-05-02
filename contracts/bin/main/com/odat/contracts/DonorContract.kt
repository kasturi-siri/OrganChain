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
 *  - [Assign]    : Organ assigned to a recipient   (1 AVAILABLE DonorState → 1 ASSIGNED)
 *  - [Expire]    : Organ viability elapsed          (1 AVAILABLE DonorState → 1 EXPIRED)
 *
 * ═══════════════════════════════════════════════════════════════
 * BUG FIX — Assign / Expire shape checks (CRITICAL)
 * ═══════════════════════════════════════════════════════════════
 * Original code:
 *   "Assign: exactly one input required"  using (tx.inputs.size  == 1)
 *   "Assign: exactly one output required" using (tx.outputs.size == 1)
 *
 * Why it broke:
 *   OrganMatchingFlow builds a SINGLE transaction that consumes a
 *   DonorState AND a RecipientState (2 inputs) and produces a
 *   DonorState (ASSIGNED) + RecipientState (MATCHED) + MatchState
 *   (3 outputs).  The raw-count checks always evaluated to false
 *   inside that multi-state transaction, so every matching attempt
 *   threw a ContractVerificationException.
 *
 * Fix:
 *   Use `tx.inputsOfType<DonorState>()` and
 *   `tx.outputsOfType<DonorState>()` — these count only states
 *   governed by this contract, regardless of how many other state
 *   types share the same transaction.
 * ═══════════════════════════════════════════════════════════════
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

            // ── Register: standalone tx (0 inputs → 1 AVAILABLE output) ──
            is Commands.Register -> {
                requireThat {
                    "Register: no input states allowed" using tx.inputs.isEmpty()
                    "Register: exactly one output state required" using (tx.outputs.size == 1)

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
                    "Register: registeredBy party must sign" using
                            (command.signers.contains(out.registeredBy.owningKey))
                }
            }

            // ── Assign: may be part of a multi-state matching tx ──────────
            is Commands.Assign -> {
                requireThat {
                    // FIXED: count only DonorState instances, not all states
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
                            (command.signers.contains(out.registeredBy.owningKey))
                }
            }

            // ── Expire: also uses type-scoped counts for consistency ───────
            is Commands.Expire -> {
                requireThat {
                    // FIXED: consistent with Assign — use type-scoped counts
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
