package com.odat.webserver.controllers

import com.odat.enums.DonorStatus
import com.odat.states.*
import com.odat.flows.RegisterDonorFlow
import com.odat.webserver.config.NodeRPCConnection
import com.odat.webserver.models.ApiResponse
import com.odat.webserver.models.DonorResponse
import com.odat.webserver.models.RegisterDonorRequest
import net.corda.core.node.services.queryBy
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import net.corda.core.messaging.vaultQueryBy

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
     *
     * Example body:
     * {
     *   "name": "John Doe",
     *   "contact": "john@example.com",
     *   "bloodType": "O_POS",
     *   "organType": "KIDNEY",
     *   "age": 35, "weightKg": 70.0, "heightCm": 175.0,
     *   "isDeceased": false, "location": "Chennai"
     * }
     */
    @PostMapping("/register")
    fun registerDonor(@RequestBody req: RegisterDonorRequest): ResponseEntity<ApiResponse<DonorResponse>> {
        return try {
            log.info("RegisterDonor: bloodType=${req.bloodType} organ=${req.organType}")

            val flowInput = DonorInput(
                name      = req.name,
                contact   = req.contact,
                bloodType = req.bloodType,
                organType = req.organType,
                age       = req.age,
                weightKg  = req.weightKg,
                heightCm  = req.heightCm,
                isDeceased= req.isDeceased,
                location  = req.location
            )

            // Start the flow and wait for completion (blocking RPC call)
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
            log.error("RegisterDonor FAILED: ${e.message}")
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
     * Returns a specific DonorState by linearId.
     */
    @GetMapping("/{linearId}")
    fun getDonor(@PathVariable linearId: String): ResponseEntity<ApiResponse<DonorResponse>> {
        val donor = rpc.proxy.vaultQueryBy<DonorState>().states
            .firstOrNull { it.state.data.linearId.toString() == linearId }
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse(success = false, message = "Donor $linearId not found")
            )
        return ResponseEntity.ok(
            ApiResponse(success = true, message = "Donor found", data = DonorResponse.from(donor.state.data))
        )
    }
}