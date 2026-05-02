package com.odat.webserver.controllers

import com.odat.enums.DonorStatus
import com.odat.flows.RegisterDonorFlow
import com.odat.states.DonorState
import com.odat.states.DonorInput
import com.odat.webserver.config.NodeRPCConnection
import com.odat.webserver.models.ApiResponse
import com.odat.webserver.models.DonorResponse
import com.odat.webserver.models.RegisterDonorRequest
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.node.services.vault.QueryCriteria
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * DonorController — REST API for donor registration and queries.
 *
 * Base path: /api/donor
 *
 * Endpoints:
 *  POST /api/donor/register          → RegisterDonorFlow
 *  GET  /api/donor/list              → All DonorStates in Vault
 *  GET  /api/donor/available         → AVAILABLE DonorStates only
 *  GET  /api/donor/{linearId}        → Single DonorState by ID
 *
 * FIXES applied:
 *   Finding #15 — getDonor() now uses LinearStateQueryCriteria to push the
 *                 linearId filter to the Corda Vault database index, replacing
 *                 the previous O(n) in-memory linear scan over all states.
 *   Finding #16 — listAvailableDonors() note: DonorState does not implement
 *                 QueryableState with a mapped schema, so status filtering cannot
 *                 be pushed to the DB via VaultCustomQueryCriteria without adding
 *                 a schema. The in-memory filter is retained with a comment
 *                 explaining the constraint and the path to remove it.
 */
@RestController
@RequestMapping("/api/donor")
class DonorController(private val rpc: NodeRPCConnection) {

    companion object {
        private val log = LoggerFactory.getLogger(DonorController::class.java)
    }

    /**
     * POST /api/donor/register
     *
     * Triggers [RegisterDonorFlow]. Encrypts PII inside the flow.
     * Returns the linearId of the created DonorState.
     */
    @PostMapping("/register")
    fun registerDonor(@RequestBody req: RegisterDonorRequest): ResponseEntity<ApiResponse<DonorResponse>> {
        return try {
            log.info("RegisterDonor: bloodType=${req.bloodType} organ=${req.organType}")

            val flowInput = DonorInput(
                name       = req.name,
                contact    = req.contact,
                bloodType  = req.bloodType,
                organType  = req.organType,
                age        = req.age,
                weightKg   = req.weightKg,
                heightCm   = req.heightCm,
                isDeceased = req.isDeceased,
                location   = req.location
            )

            val signedTx = rpc.proxy.startFlowDynamic(
                RegisterDonorFlow::class.java, flowInput
            ).returnValue.get()

            val donorState = signedTx.coreTransaction
                .outputsOfType(DonorState::class.java).single()

            log.info("RegisterDonor SUCCESS: linearId=${donorState.linearId}")
            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse(
                    success = true,
                    message = "Donor registered successfully. Encrypted and stored on Corda ledger.",
                    data    = DonorResponse.from(donorState)
                )
            )
        } catch (e: Exception) {
            log.error("RegisterDonor FAILED: ${e.message}", e)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiResponse(success = false, message = e.message ?: "Unknown error")
            )
        }
    }

    /**
     * GET /api/donor/list
     * Returns all DonorStates visible in this node's Vault.
     */
    @GetMapping("/list")
    fun listAllDonors(): ResponseEntity<ApiResponse<List<DonorResponse>>> {
        val donors = rpc.proxy.vaultQueryBy<DonorState>().states
            .map { DonorResponse.from(it.state.data) }
        return ResponseEntity.ok(
            ApiResponse(success = true, message = "${donors.size} donor(s) found", data = donors)
        )
    }

    /**
     * GET /api/donor/available
     * Returns only AVAILABLE DonorStates (organ not yet assigned).
     *
     * Note (Finding #16): Status filtering is done in-memory because DonorState
     * does not implement QueryableState with a mapped schema. To push this filter
     * to the DB, implement DonorSchemaV1 (PersistentDonorState with a status column)
     * and use VaultCustomQueryCriteria with Builder.equal(schema::status, AVAILABLE).
     */
    @GetMapping("/available")
    fun listAvailableDonors(): ResponseEntity<ApiResponse<List<DonorResponse>>> {
        val donors = rpc.proxy.vaultQueryBy<DonorState>().states
            .filter { it.state.data.status == DonorStatus.AVAILABLE }
            .map { DonorResponse.from(it.state.data) }
        return ResponseEntity.ok(
            ApiResponse(success = true, message = "${donors.size} available donor(s)", data = donors)
        )
    }

    /**
     * GET /api/donor/{linearId}
     *
     * FIX #15: Uses LinearStateQueryCriteria for an indexed DB lookup by linearId,
     * replacing the previous full in-memory scan of all unconsumed states.
     */
    @GetMapping("/{linearId}")
    fun getDonor(@PathVariable linearId: String): ResponseEntity<ApiResponse<DonorResponse>> {
        return try {
            val uid      = UniqueIdentifier(id = UUID.fromString(linearId))
            val criteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(uid))
            val donor    = rpc.proxy.vaultQueryBy<DonorState>(criteria).states.firstOrNull()
                ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse(success = false, message = "Donor $linearId not found")
                )
            ResponseEntity.ok(
                ApiResponse(success = true, message = "Donor found", data = DonorResponse.from(donor.state.data))
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiResponse(success = false, message = "Invalid linearId format: $linearId")
            )
        }
    }
}
