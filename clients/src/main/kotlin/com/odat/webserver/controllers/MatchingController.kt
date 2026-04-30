package com.odat.webserver.controllers

import com.odat.enums.MatchStatus
import com.odat.flows.*
import com.odat.states.DonorState
import com.odat.states.MatchState
import com.odat.states.TransportState
import com.odat.webserver.config.NodeRPCConnection
import com.odat.webserver.models.*
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.node.services.vault.QueryCriteria
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * MatchingController — REST API for organ matching operations.
 *
 * Base path: /api/match
 *
 * Endpoints:
 *  POST /api/match/trigger/{donorLinearId}        → OrganMatchingFlow  (MatchingAuthority node)
 *  POST /api/match/confirm/{matchLinearId}        → ConfirmMatchFlow   + NotifyMatchedPartiesFlow
 *  POST /api/match/reject/{matchLinearId}         → RejectMatchFlow
 *  GET  /api/match/list                           → All MatchStates
 *  GET  /api/match/pending                        → PENDING MatchStates
 *  GET  /api/match/{linearId}                     → Single MatchState
 *  GET  /api/match/summary/{matchLinearId}        → Decrypted MatchSummary (MA node only)
 */
@RestController
@RequestMapping("/api/match")
class MatchingController(private val rpc: NodeRPCConnection) {

    companion object {
        private val log = LoggerFactory.getLogger(MatchingController::class.java)
    }

    /**
     * POST /api/match/trigger/{donorLinearId}
     *
     * Triggers [OrganMatchingFlow] on the MatchingAuthority node.
     * The MA decrypts both donor and all waiting recipient states, runs
     * Algorithm 1, and creates a PENDING MatchState if a match is found.
     */
    @PostMapping("/trigger/{donorLinearId}")
    fun triggerMatching(@PathVariable donorLinearId: String): ResponseEntity<ApiResponse<MatchResponse?>> {
        return try {
            log.info("Triggering OrganMatchingFlow (MatchingAuthority) for donor: $donorLinearId")

            val donorRef = rpc.proxy.vaultQueryBy<DonorState>().states
                .firstOrNull { it.state.data.linearId.toString() == donorLinearId }
                ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse(false, "Donor $donorLinearId not found in Vault")
                )

            val matchRef = rpc.proxy.startFlowDynamic(
                OrganMatchingFlow::class.java, donorRef
            ).returnValue.get()

            if (matchRef == null) {
                log.info("OrganMatchingFlow: No compatible recipient found")
                return ResponseEntity.ok(
                    ApiResponse(true, "No compatible recipient found. Waitlist will be re-evaluated when a new registration arrives.", null)
                )
            }

            val matchState = matchRef.state.data
            log.info("Match found — matchId=${matchState.linearId} score=${matchState.matchScore} organ=${matchState.organType}")
            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse(
                    success = true,
                    message = "Match found. Score=${matchState.matchScore}. All fields remain encrypted pending admin confirmation.",
                    data    = MatchResponse.from(matchState)
                )
            )
        } catch (e: Exception) {
            log.error("OrganMatchingFlow FAILED: ${e.message}", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse(false, e.message ?: "Flow error")
            )
        }
    }

    /**
     * POST /api/match/confirm/{matchLinearId}
     *
     * Confirms a PENDING match and automatically triggers [NotifyMatchedPartiesFlow],
     * which decrypts the matched donor/recipient details and sends a [MatchSummary]
     * to both hospitals over TLS-secured Corda P2P.
     */
    @PostMapping("/confirm/{matchLinearId}")
    fun confirmMatch(@PathVariable matchLinearId: String): ResponseEntity<ApiResponse<MatchResponse>> {
        return try {
            log.info("ConfirmMatchFlow: matchId=$matchLinearId")
            val linearId = UniqueIdentifier.fromString(matchLinearId)
            val signedTx = rpc.proxy.startFlowDynamic(
                ConfirmMatchFlow::class.java, linearId
            ).returnValue.get()

            val confirmed = signedTx.coreTransaction.outputsOfType(MatchState::class.java).single()
            ResponseEntity.ok(
                ApiResponse(
                    true,
                    "Match CONFIRMED. Decrypted details securely dispatched to donor and recipient hospitals. Transport dispatch initiated.",
                    MatchResponse.from(confirmed)
                )
            )
        } catch (e: Exception) {
            log.error("ConfirmMatchFlow FAILED: ${e.message}", e)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiResponse(false, e.message ?: "Flow error")
            )
        }
    }

    /**
     * POST /api/match/reject/{matchLinearId}
     * Body: { "reason": "Cross-match lab result negative" }
     */
    @PostMapping("/reject/{matchLinearId}")
    fun rejectMatch(
        @PathVariable matchLinearId: String,
        @RequestBody req: RejectMatchRequest
    ): ResponseEntity<ApiResponse<MatchResponse>> {
        return try {
            log.info("RejectMatchFlow: matchId=$matchLinearId reason=${req.reason}")
            val linearId = UniqueIdentifier.fromString(matchLinearId)
            val signedTx = rpc.proxy.startFlowDynamic(
                RejectMatchFlow::class.java, linearId, req.reason
            ).returnValue.get()

            val rejected = signedTx.coreTransaction.outputsOfType(MatchState::class.java).single()
            ResponseEntity.ok(
                ApiResponse(true, "Match REJECTED. Patient returned to waitlist.", MatchResponse.from(rejected))
            )
        } catch (e: Exception) {
            log.error("RejectMatchFlow FAILED: ${e.message}", e)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiResponse(false, e.message ?: "Flow error")
            )
        }
    }

    @GetMapping("/list")
    fun listAll(): ResponseEntity<ApiResponse<List<MatchResponse>>> {
        val matches = rpc.proxy.vaultQueryBy<MatchState>().states
            .map { MatchResponse.from(it.state.data) }
            .sortedByDescending { it.matchedAt }
        return ResponseEntity.ok(ApiResponse(true, "${matches.size} match(es)", matches))
    }

    @GetMapping("/pending")
    fun listPending(): ResponseEntity<ApiResponse<List<MatchResponse>>> {
        val pending = rpc.proxy.vaultQueryBy<MatchState>().states
            .filter { it.state.data.status == MatchStatus.PENDING_CONFIRMATION }
            .map { MatchResponse.from(it.state.data) }
        return ResponseEntity.ok(ApiResponse(true, "${pending.size} pending match(es)", pending))
    }

    @GetMapping("/{linearId}")
    fun getMatch(@PathVariable linearId: String): ResponseEntity<ApiResponse<MatchResponse>> {
        return try {
            val uid      = UniqueIdentifier(id = UUID.fromString(linearId))
            val criteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(uid))
            val state    = rpc.proxy.vaultQueryBy<MatchState>(criteria).states.firstOrNull()
                ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse(false, "Match $linearId not found")
                )
            ResponseEntity.ok(ApiResponse(true, "Found", MatchResponse.from(state.state.data)))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiResponse(false, "Invalid linearId format: $linearId")
            )
        }
    }

    /**
     * GET /api/match/summary/{matchLinearId}
     *
     * Returns the decrypted [MatchSummaryResponse] for a CONFIRMED match.
     *
     * SECURITY: This endpoint must be served by the MatchingAuthority node's
     * Spring Boot server — it calls [GetMatchSummaryFlow] which uses the medical
     * key held exclusively by that node. Hospital nodes connecting to this endpoint
     * receive the plaintext summary over HTTPS.
     *
     * Returns 403 if called on a non-MatchingAuthority node (the flow will throw
     * a FlowException because the node lacks the medical key for decryption).
     */
    @GetMapping("/summary/{matchLinearId}")
    fun getMatchSummary(@PathVariable matchLinearId: String): ResponseEntity<ApiResponse<MatchSummaryResponse>> {
        return try {
            val linearId = UniqueIdentifier.fromString(matchLinearId)
            log.info("GetMatchSummaryFlow: matchId=$matchLinearId")

            val summary = rpc.proxy.startFlowDynamic(
                GetMatchSummaryFlow::class.java, linearId
            ).returnValue.get()

            ResponseEntity.ok(
                ApiResponse(
                    success = true,
                    message = "Match summary decrypted and returned. Handle with appropriate access controls.",
                    data    = MatchSummaryResponse.from(summary)
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiResponse(false, "Invalid matchLinearId format: $matchLinearId")
            )
        } catch (e: Exception) {
            log.error("GetMatchSummaryFlow FAILED: ${e.message}", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse(false, e.message ?: "Summary retrieval failed")
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TransportController (unchanged except for updated import path)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * TransportController — REST API for organ transport management.
 * Base path: /api/transport
 */
@RestController
@RequestMapping("/api/transport")
class TransportController(private val rpc: NodeRPCConnection) {

    companion object {
        private val log = LoggerFactory.getLogger(TransportController::class.java)
    }

    @PostMapping("/dispatch/{matchLinearId}")
    fun dispatchTransport(@PathVariable matchLinearId: String): ResponseEntity<ApiResponse<TransportResponse>> {
        return try {
            val linearId = UniqueIdentifier.fromString(matchLinearId)
            val signedTx = rpc.proxy.startFlowDynamic(
                DispatchTransportFlow::class.java, linearId
            ).returnValue.get()
            val transport = signedTx.coreTransaction.outputsOfType(TransportState::class.java).single()
            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse(true, "Transport dispatched. Viability: ${transport.viabilityWindowHours}h", TransportResponse.from(transport))
            )
        } catch (e: Exception) {
            log.error("DispatchTransportFlow FAILED: ${e.message}", e)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse(false, e.message ?: "Flow error"))
        }
    }

    @PostMapping("/update/{transportLinearId}")
    fun updateStatus(
        @PathVariable transportLinearId: String,
        @RequestBody req: UpdateTransportStatusRequest
    ): ResponseEntity<ApiResponse<TransportResponse>> {
        return try {
            val linearId = UniqueIdentifier.fromString(transportLinearId)
            val signedTx = rpc.proxy.startFlowDynamic(
                UpdateTransportStatusFlow::class.java, linearId, req.newStatus
            ).returnValue.get()
            val transport = signedTx.coreTransaction.outputsOfType(TransportState::class.java).single()
            ResponseEntity.ok(ApiResponse(true, "Status updated to ${transport.status}", TransportResponse.from(transport)))
        } catch (e: Exception) {
            log.error("UpdateTransportStatusFlow FAILED: ${e.message}", e)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse(false, e.message ?: "Flow error"))
        }
    }

    @GetMapping("/list")
    fun listAll(): ResponseEntity<ApiResponse<List<TransportResponse>>> {
        val transports = rpc.proxy.vaultQueryBy<TransportState>().states.map { TransportResponse.from(it.state.data) }
        return ResponseEntity.ok(ApiResponse(true, "${transports.size} transport(s)", transports))
    }

    @GetMapping("/{linearId}")
    fun getTransport(@PathVariable linearId: String): ResponseEntity<ApiResponse<TransportResponse>> {
        return try {
            val uid      = UniqueIdentifier(id = UUID.fromString(linearId))
            val criteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(uid))
            val state    = rpc.proxy.vaultQueryBy<TransportState>(criteria).states.firstOrNull()
                ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse(false, "Transport $linearId not found"))
            ResponseEntity.ok(ApiResponse(true, "Found", TransportResponse.from(state.state.data)))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse(false, "Invalid linearId: $linearId"))
        }
    }
}
