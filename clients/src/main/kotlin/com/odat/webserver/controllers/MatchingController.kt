package com.odat.webserver.controllers

import com.odat.flows.*
import com.odat.states.DonorState
import com.odat.states.MatchState
import com.odat.states.TransportState
import com.odat.webserver.config.NodeRPCConnection
import com.odat.webserver.models.*
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.node.services.queryBy
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

// ─────────────────────────────────────────────────────────────────────────────
// MatchingController
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MatchingController — REST API for organ matching operations.
 *
 * Base path: /api/match
 *
 * Endpoints:
 *  POST /api/match/trigger/{donorLinearId}   → OrganMatchingFlow
 *  POST /api/match/confirm/{matchLinearId}   → ConfirmMatchFlow
 *  POST /api/match/reject/{matchLinearId}    → RejectMatchFlow
 *  GET  /api/match/list                      → All MatchStates
 *  GET  /api/match/pending                   → PENDING MatchStates only
 *  GET  /api/match/{linearId}                → Single MatchState
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
     * Manually triggers the organ matching algorithm for a given donor.
     * In production this is also triggered automatically via the scheduler
     * service whenever a new DonorState is registered.
     */
    @PostMapping("/trigger/{donorLinearId}")
    fun triggerMatching(@PathVariable donorLinearId: String): ResponseEntity<ApiResponse<MatchResponse?>> {
        return try {
            log.info("Triggering OrganMatchingFlow for donor: $donorLinearId")

            // Locate the DonorState in the Vault
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
                    ApiResponse(true, "No compatible recipient found. Patient will be notified when a match is available.", null)
                )
            }

            val matchState = matchRef.state.data
            log.info("Match found! matchId=${matchState.linearId} score=${matchState.matchScore}")
            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse(
                    success = true,
                    message = "Match found! Score=${matchState.matchScore}. Pending admin confirmation.",
                    data    = MatchResponse.from(matchState)
                )
            )
        } catch (e: Exception) {
            log.error("OrganMatchingFlow FAILED: ${e.message}")
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse(false, e.message ?: "Flow error")
            )
        }
    }

    /**
     * POST /api/match/confirm/{matchLinearId}
     *
     * Admin confirms a PENDING match → CONFIRMED.
     * Only callable from the AdminNode.
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
                ApiResponse(true, "Match CONFIRMED. Transport dispatch initiated.", MatchResponse.from(confirmed))
            )
        } catch (e: Exception) {
            log.error("ConfirmMatchFlow FAILED: ${e.message}")
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiResponse(false, e.message ?: "Flow error")
            )
        }
    }

    /**
     * POST /api/match/reject/{matchLinearId}
     *
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
                ApiResponse(true, "Match REJECTED. Waitlist restored.", MatchResponse.from(rejected))
            )
        } catch (e: Exception) {
            log.error("RejectMatchFlow FAILED: ${e.message}")
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
            .filter { it.state.data.status == com.odat.enums.MatchStatus.PENDING_CONFIRMATION }
            .map { MatchResponse.from(it.state.data) }
        return ResponseEntity.ok(ApiResponse(true, "${pending.size} pending match(es)", pending))
    }

    @GetMapping("/{linearId}")
    fun getMatch(@PathVariable linearId: String): ResponseEntity<ApiResponse<MatchResponse>> {
        val state = rpc.proxy.vaultQueryBy<MatchState>().states
            .firstOrNull { it.state.data.linearId.toString() == linearId }
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse(false, "Match $linearId not found")
            )
        return ResponseEntity.ok(ApiResponse(true, "Found", MatchResponse.from(state.state.data)))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TransportController
// ─────────────────────────────────────────────────────────────────────────────

/**
 * TransportController — REST API for organ transport management.
 *
 * Base path: /api/transport
 *
 * Endpoints:
 *  POST /api/transport/dispatch/{matchLinearId}       → DispatchTransportFlow
 *  POST /api/transport/update/{transportLinearId}     → UpdateTransportStatusFlow
 *  GET  /api/transport/list                           → All TransportStates
 *  GET  /api/transport/{linearId}                     → Single TransportState
 */
@RestController
@RequestMapping("/api/transport")
class TransportController(private val rpc: NodeRPCConnection) {

    companion object {
        private val log = LoggerFactory.getLogger(TransportController::class.java)
    }

    /**
     * POST /api/transport/dispatch/{matchLinearId}
     * Admin dispatches transport for a CONFIRMED match.
     */
    @PostMapping("/dispatch/{matchLinearId}")
    fun dispatchTransport(@PathVariable matchLinearId: String): ResponseEntity<ApiResponse<TransportResponse>> {
        return try {
            val linearId = UniqueIdentifier.fromString(matchLinearId)
            log.info("DispatchTransportFlow: matchId=$matchLinearId")

            val signedTx = rpc.proxy.startFlowDynamic(
                DispatchTransportFlow::class.java, linearId
            ).returnValue.get()

            val transport = signedTx.coreTransaction
                .outputsOfType(TransportState::class.java).single()

            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse(
                    true,
                    "Transport dispatched. Viability window: ${transport.viabilityWindowHours}h",
                    TransportResponse.from(transport)
                )
            )
        } catch (e: Exception) {
            log.error("DispatchTransportFlow FAILED: ${e.message}")
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiResponse(false, e.message ?: "Flow error")
            )
        }
    }

    /**
     * POST /api/transport/update/{transportLinearId}
     * Body: { "newStatus": "IN_TRANSIT" }
     */
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

            val transport = signedTx.coreTransaction
                .outputsOfType(TransportState::class.java).single()
            ResponseEntity.ok(
                ApiResponse(true, "Transport status updated to ${transport.status}", TransportResponse.from(transport))
            )
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiResponse(false, e.message ?: "Flow error")
            )
        }
    }

    @GetMapping("/list")
    fun listAll(): ResponseEntity<ApiResponse<List<TransportResponse>>> {
        val transports = rpc.proxy.vaultQueryBy<TransportState>().states
            .map { TransportResponse.from(it.state.data) }
        return ResponseEntity.ok(ApiResponse(true, "${transports.size} transport(s)", transports))
    }

    @GetMapping("/{linearId}")
    fun getTransport(@PathVariable linearId: String): ResponseEntity<ApiResponse<TransportResponse>> {
        val state = rpc.proxy.vaultQueryBy<TransportState>().states
            .firstOrNull { it.state.data.linearId.toString() == linearId }
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse(false, "Transport $linearId not found")
            )
        return ResponseEntity.ok(ApiResponse(true, "Found", TransportResponse.from(state.state.data)))
    }
}
