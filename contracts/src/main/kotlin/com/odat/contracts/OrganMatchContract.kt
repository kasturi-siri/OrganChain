package com.odat.contracts

import com.odat.enums.CrossMatchResult
import com.odat.enums.MatchStatus
import com.odat.enums.TransportStatus
import com.odat.states.MatchState
import com.odat.states.TransportState
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction

// ─────────────────────────────────────────────────────────────────────────────
// OrganMatchContract
// ─────────────────────────────────────────────────────────────────────────────

/**
 * OrganMatchContract — governs [MatchState] lifecycle.
 *
 * Commands:
 *  - [FindMatch]    : Algorithm produces a new PENDING match       (no match inputs)
 *  - [ConfirmMatch] : Admin confirms PENDING → CONFIRMED
 *  - [RejectMatch]  : Admin rejects  PENDING → REJECTED
 */
class OrganMatchContract : Contract {

    companion object {
        @JvmStatic
        val CONTRACT_ID = "com.odat.contracts.OrganMatchContract"
    }

    interface Commands : CommandData {
        class FindMatch    : Commands
        class ConfirmMatch : Commands
        class RejectMatch  : Commands
    }

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()

        when (command.value) {

            is Commands.FindMatch -> {
                requireThat {
                    // The flow also consumes a DonorState + RecipientState input —
                    // those are governed by their own contracts.
                    "FindMatch: exactly one MatchState output required" using
                            (tx.outputsOfType<MatchState>().size == 1)

                    val out = tx.outputsOfType<MatchState>().single()
                    "FindMatch: status must be PENDING_CONFIRMATION" using
                            (out.status == MatchStatus.PENDING_CONFIRMATION)
                    "FindMatch: matchScore must be positive" using
                            (out.matchScore > 0.0)
                    "FindMatch: crossMatchResult must be POSITIVE" using
                            (out.crossMatchResult == CrossMatchResult.POSITIVE)

                    // Both hospitals must sign — ensures cross-hospital matching consent
                    "FindMatch: donor hospital must sign" using
                            command.signers.contains(out.donorHospital.owningKey)
                    "FindMatch: recipient hospital must sign" using
                            command.signers.contains(out.recipientHospital.owningKey)
                }
            }

            is Commands.ConfirmMatch -> {
                requireThat {
                    "ConfirmMatch: one MatchState input required" using
                            (tx.inputsOfType<MatchState>().size == 1)
                    "ConfirmMatch: one MatchState output required" using
                            (tx.outputsOfType<MatchState>().size == 1)

                    val inp = tx.inputsOfType<MatchState>().single()
                    val out = tx.outputsOfType<MatchState>().single()
                    "ConfirmMatch: input must be PENDING" using
                            (inp.status == MatchStatus.PENDING_CONFIRMATION)
                    "ConfirmMatch: output must be CONFIRMED" using
                            (out.status == MatchStatus.CONFIRMED)
                    "ConfirmMatch: linearId must be unchanged" using
                            (inp.linearId == out.linearId)
                    "ConfirmMatch: admin must sign" using
                            command.signers.contains(out.adminNode.owningKey)
                }
            }

            is Commands.RejectMatch -> {
                requireThat {
                    val inp = tx.inputsOfType<MatchState>().single()
                    val out = tx.outputsOfType<MatchState>().single()
                    "RejectMatch: input must be PENDING" using
                            (inp.status == MatchStatus.PENDING_CONFIRMATION)
                    "RejectMatch: output must be REJECTED" using
                            (out.status == MatchStatus.REJECTED)
                    "RejectMatch: rejection reason must be provided" using
                            (!out.rejectionReason.isNullOrBlank())
                    "RejectMatch: admin must sign" using
                            command.signers.contains(out.adminNode.owningKey)
                }
            }

            else -> throw IllegalArgumentException("Unknown OrganMatchContract command.")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TransportContract
// ─────────────────────────────────────────────────────────────────────────────

/**
 * TransportContract — governs [TransportState] lifecycle.
 *
 * Commands:
 *  - [Dispatch]  : Transport assignment created after match confirmed
 *  - [UpdateStatus] : Transporter updates status (IN_TRANSIT, DELIVERED, FAILED)
 */
class TransportContract : Contract {

    companion object {
        @JvmStatic
        val CONTRACT_ID = "com.odat.contracts.TransportContract"
    }

    interface Commands : CommandData {
        class Dispatch     : Commands
        class UpdateStatus : Commands
    }

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()

        when (command.value) {

            is Commands.Dispatch -> {
                requireThat {
                    "Dispatch: no TransportState inputs allowed" using
                            tx.inputsOfType<TransportState>().isEmpty()
                    "Dispatch: exactly one TransportState output required" using
                            (tx.outputsOfType<TransportState>().size == 1)

                    val out = tx.outputsOfType<TransportState>().single()
                    "Dispatch: status must be DISPATCHED" using
                            (out.status == TransportStatus.DISPATCHED)
                    "Dispatch: viability window must be positive" using
                            (out.viabilityWindowHours > 0)
                    "Dispatch: transporter must sign" using
                            command.signers.contains(out.transporterNode.owningKey)
                    "Dispatch: origin hospital must sign" using
                            command.signers.contains(out.originHospital.owningKey)
                }
            }

            is Commands.UpdateStatus -> {
                requireThat {
                    val inp = tx.inputsOfType<TransportState>().single()
                    val out = tx.outputsOfType<TransportState>().single()
                    "UpdateStatus: linearId must be unchanged" using
                            (inp.linearId == out.linearId)
                    "UpdateStatus: DELIVERED → no further update allowed" using
                            (inp.status != TransportStatus.DELIVERED)
                    "UpdateStatus: transporter must sign" using
                            command.signers.contains(out.transporterNode.owningKey)
                }
            }

            else -> throw IllegalArgumentException("Unknown TransportContract command.")
        }
    }
}
