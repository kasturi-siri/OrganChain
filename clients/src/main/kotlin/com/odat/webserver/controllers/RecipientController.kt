package com.odat.webserver.controllers

import com.odat.enums.RecipientStatus
import com.odat.flows.RecipientInput
import com.odat.flows.RegisterRecipientFlow
import com.odat.states.RecipientState
import com.odat.webserver.config.NodeRPCConnection
import com.odat.webserver.models.ApiResponse
import com.odat.webserver.models.RecipientResponse
import com.odat.webserver.models.RegisterRecipientRequest
import net.corda.core.node.services.queryBy
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import net.corda.core.messaging.vaultQueryBy


/**
 * RecipientController — REST API for patient registration and queries.
 *
 * Base path: /api/recipient
 *
 * Endpoints:
 *  POST /api/recipient/register     → RegisterRecipientFlow
 *  GET  /api/recipient/list         → All RecipientStates
 *  GET  /api/recipient/waiting      → WAITING RecipientStates
 *  GET  /api/recipient/{linearId}   → Single RecipientState
 */
@RestController
@RequestMapping("/api/recipient")
class RecipientController(private val rpc: NodeRPCConnection) {

    companion object {
        private val log = LoggerFactory.getLogger(RecipientController::class.java)
    }

    /**
     * POST /api/recipient/register
     *
     * Example body:
     * {
     *   "name": "Jane Smith",
     *   "contact": "jane@hospital.com",
     *   "bloodType": "O_POS",
     *   "organNeeded": "KIDNEY",
     *   "age": 32, "weightKg": 62.0, "heightCm": 165.0,
     *   "conditionScore": 8, "serialNumber": 42,
     *   "hasPairedDonor": false, "location": "Chennai"
     * }
     */
    @PostMapping("/register")
    fun registerRecipient(@RequestBody req: RegisterRecipientRequest): ResponseEntity<ApiResponse<RecipientResponse>> {
        return try {
            log.info("RegisterRecipient: organ=${req.organNeeded} blood=${req.bloodType} score=${req.conditionScore}")

            val flowInput = RecipientInput(
                name          = req.name,
                contact       = req.contact,
                bloodType     = req.bloodType,
                organNeeded   = req.organNeeded,
                age           = req.age,
                weightKg      = req.weightKg,
                heightCm      = req.heightCm,
                conditionScore= req.conditionScore,
                serialNumber  = req.serialNumber,
                hasPairedDonor= req.hasPairedDonor,
                location      = req.location
            )

            val signedTx = rpc.proxy.startFlowDynamic(
                RegisterRecipientFlow::class.java, flowInput
            ).returnValue.get()

            val recipientState = signedTx.coreTransaction
                .outputsOfType(RecipientState::class.java).single()

            log.info("RegisterRecipient SUCCESS: linearId=${recipientState.linearId}")
            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse(
                    success = true,
                    message = "Patient registered on waitlist. PII encrypted and stored on Corda ledger.",
                    data    = RecipientResponse.from(recipientState)
                )
            )
        } catch (e: Exception) {
            log.error("RegisterRecipient FAILED: ${e.message}")
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiResponse(success = false, message = e.message ?: "Unknown error")
            )
        }
    }

    @GetMapping("/list")
    fun listAll(): ResponseEntity<ApiResponse<List<RecipientResponse>>> {
        val all = rpc.proxy.vaultQueryBy<RecipientState>().states
            .map { RecipientResponse.from(it.state.data) }
        return ResponseEntity.ok(ApiResponse(true, "${all.size} recipient(s)", all))
    }

    @GetMapping("/waiting")
    fun listWaiting(): ResponseEntity<ApiResponse<List<RecipientResponse>>> {
        val waiting = rpc.proxy.vaultQueryBy<RecipientState>().states
            .filter { it.state.data.status == RecipientStatus.WAITING }
            .map { RecipientResponse.from(it.state.data) }
        return ResponseEntity.ok(ApiResponse(true, "${waiting.size} waiting patient(s)", waiting))
    }

    @GetMapping("/{linearId}")
    fun getRecipient(@PathVariable linearId: String): ResponseEntity<ApiResponse<RecipientResponse>> {
        val state = rpc.proxy.vaultQueryBy<RecipientState>().states
            .firstOrNull { it.state.data.linearId.toString() == linearId }
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse(false, "Recipient $linearId not found")
            )
        return ResponseEntity.ok(ApiResponse(true, "Found", RecipientResponse.from(state.state.data)))
    }
}