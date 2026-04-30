package com.odat.webserver.models

import com.odat.enums.*
import com.odat.states.DonorState
import com.odat.states.MatchState
import com.odat.states.MatchSummary
import com.odat.states.RecipientState
import com.odat.states.TransportState
import java.time.Instant

// ─────────────────────────────────────────────────────────────────────────────
// Request bodies (received from client)
// ─────────────────────────────────────────────────────────────────────────────

data class RegisterDonorRequest(
    val name: String,
    val contact: String,
    val bloodType: BloodType,
    val organType: OrganType,
    val age: Int,
    val weightKg: Double,
    val heightCm: Double,
    val isDeceased: Boolean,
    val location: String
)

data class RegisterRecipientRequest(
    val name: String,
    val contact: String,
    val bloodType: BloodType,
    val organNeeded: OrganType,
    val age: Int,
    val weightKg: Double,
    val heightCm: Double,
    val conditionScore: Int,
    val serialNumber: Int,
    val hasPairedDonor: Boolean,
    val location: String
)

data class RejectMatchRequest(val reason: String)

data class UpdateTransportStatusRequest(val newStatus: TransportStatus)

// ─────────────────────────────────────────────────────────────────────────────
// Response bodies (returned to client)
// ─────────────────────────────────────────────────────────────────────────────

data class ApiResponse<T>(
    val success: Boolean,
    val message: String,
    val data: T? = null
)

/**
 * DonorResponse — safe public projection of [DonorState].
 *
 * Medical fields (bloodType, organType, age, weight, height, location, isDeceased)
 * are NOT included — they remain encrypted on the ledger and are only decrypted
 * by the MatchingAuthority during the matching process.
 *
 * Exposes: linearId, registration metadata, status, registering hospital.
 * This is intentionally minimal — use GET /api/match/summary/{id} for clinical
 * details after a match is confirmed.
 */
data class DonorResponse(
    val linearId:         String,
    val status:           DonorStatus,
    val registeredBy:     String,
    val registrationTime: Instant
    // Medical fields intentionally omitted — stored encrypted on ledger.
    // Decryption is exclusively performed by the MatchingAuthority.
) {
    companion object {
        fun from(s: DonorState) = DonorResponse(
            linearId         = s.linearId.toString(),
            status           = s.status,
            registeredBy     = s.registeredBy.name.organisation,
            registrationTime = s.registrationTime
        )
    }
}

/**
 * RecipientResponse — safe public projection of [RecipientState].
 * Same rationale as [DonorResponse] — medical fields are not exposed.
 */
data class RecipientResponse(
    val linearId:         String,
    val status:           RecipientStatus,
    val registeredBy:     String,
    val registrationTime: Instant
) {
    companion object {
        fun from(s: RecipientState) = RecipientResponse(
            linearId         = s.linearId.toString(),
            status           = s.status,
            registeredBy     = s.registeredBy.name.organisation,
            registrationTime = s.registrationTime
        )
    }
}

/**
 * MatchResponse — projection of [MatchState].
 *
 * Operational metadata (matchScore, crossMatchResult, organType, status) are
 * included — these are the fields stored in plaintext for administrative use.
 * Patient PII is NOT here; use [MatchSummaryResponse] (GET /api/match/summary/{id})
 * which is only accessible after confirmation.
 */
data class MatchResponse(
    val linearId:          String,
    val matchScore:        Double,
    val crossMatchResult:  CrossMatchResult,
    val organType:         OrganType,
    val donorHospital:     String,
    val recipientHospital: String,
    val matchingAuthority: String,
    val status:            MatchStatus,
    val matchedAt:         Instant,
    val resolvedAt:        Instant?,
    val rejectionReason:   String?
) {
    companion object {
        fun from(s: MatchState) = MatchResponse(
            linearId          = s.linearId.toString(),
            matchScore        = s.matchScore,
            crossMatchResult  = s.crossMatchResult,
            organType         = s.organType,
            donorHospital     = s.donorHospital.name.organisation,
            recipientHospital = s.recipientHospital.name.organisation,
            matchingAuthority = s.matchingAuthority.name.organisation,
            status            = s.status,
            matchedAt         = s.matchedAt,
            resolvedAt        = s.resolvedAt,
            rejectionReason   = s.rejectionReason
        )
    }
}

/**
 * MatchSummaryResponse — wraps [MatchSummary] for the REST layer.
 *
 * Returned by GET /api/match/summary/{matchLinearId}.
 * Only available for CONFIRMED matches; requires the requesting node to have
 * access to the medical decryption key (MatchingAuthority node).
 *
 * Contains the decrypted donor and recipient details needed for the transplant
 * to proceed.
 */
data class MatchSummaryResponse(
    val matchLinearId: String,
    val matchScore:    Double,

    // Donor details (shared with recipient hospital)
    val donorName:       String,
    val donorContact:    String,
    val donorBloodType:  String,
    val donorOrganType:  String,
    val donorLocation:   String,
    val donorIsDeceased: Boolean,

    // Recipient details (shared with donor hospital)
    val recipientName:      String,
    val recipientContact:   String,
    val recipientBloodType: String,
    val recipientOrganType: String,
    val recipientLocation:  String,
    val recipientCondition: Int
) {
    companion object {
        fun from(s: MatchSummary) = MatchSummaryResponse(
            matchLinearId   = s.matchLinearId,
            matchScore      = s.matchScore,
            donorName       = s.donorName,
            donorContact    = s.donorContact,
            donorBloodType  = s.donorBloodType,
            donorOrganType  = s.donorOrganType,
            donorLocation   = s.donorLocation,
            donorIsDeceased = s.donorIsDeceased,
            recipientName      = s.recipientName,
            recipientContact   = s.recipientContact,
            recipientBloodType = s.recipientBloodType,
            recipientOrganType = s.recipientOrganType,
            recipientLocation  = s.recipientLocation,
            recipientCondition = s.recipientCondition
        )
    }
}

data class TransportResponse(
    val linearId:             String,
    val matchId:              String,
    val organType:            OrganType,
    val originHospital:       String,
    val destinationHospital:  String,
    val transporter:          String,
    val viabilityWindowHours: Int,
    val status:               TransportStatus,
    val dispatchTime:         Instant,
    val deliveredAt:          Instant?
) {
    companion object {
        fun from(s: TransportState) = TransportResponse(
            linearId             = s.linearId.toString(),
            matchId              = s.matchId.toString(),
            organType            = s.organType,
            originHospital       = s.originHospital.name.organisation,
            destinationHospital  = s.destinationHospital.name.organisation,
            transporter          = s.transporterNode.name.organisation,
            viabilityWindowHours = s.viabilityWindowHours,
            status               = s.status,
            dispatchTime         = s.dispatchTime,
            deliveredAt          = s.deliveredAt
        )
    }
}
