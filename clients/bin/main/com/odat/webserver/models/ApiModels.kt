package com.odat.webserver.models

import com.odat.enums.*
import com.odat.states.DonorState
import com.odat.states.MatchState
import com.odat.states.RecipientState
import com.odat.states.TransportState
import java.time.Instant

// FIX — Finding #4: removed unused `import net.corda.core.contracts.UniqueIdentifier`.
//   UniqueIdentifier is never directly referenced in this file. The from() factory
//   functions only call .toString() on state fields (s.linearId, s.matchId), which
//   resolves through the state type — no explicit import is needed for that.

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

data class RejectMatchRequest(
    val reason: String
)

data class UpdateTransportStatusRequest(
    val newStatus: TransportStatus
)

// ─────────────────────────────────────────────────────────────────────────────
// Response bodies (returned to client)
// ─────────────────────────────────────────────────────────────────────────────

data class ApiResponse<T>(
    val success: Boolean,
    val message: String,
    val data: T? = null
)

data class DonorResponse(
    val linearId: String,
    val bloodType: BloodType,
    val organType: OrganType,
    val age: Int,
    val location: String,
    val isDeceased: Boolean,
    val status: DonorStatus,
    val registeredBy: String,
    val registrationTime: Instant
) {
    companion object {
        fun from(s: DonorState) = DonorResponse(
            linearId         = s.linearId.toString(),
            bloodType        = s.bloodType,
            organType        = s.organType,
            age              = s.age,
            location         = s.location,
            isDeceased       = s.isDeceased,
            status           = s.status,
            registeredBy     = s.registeredBy.name.organisation,
            registrationTime = s.registrationTime
        )
        // Note: encrypted name/contact intentionally NOT included in API response
    }
}

data class RecipientResponse(
    val linearId: String,
    val bloodType: BloodType,
    val organNeeded: OrganType,
    val age: Int,
    val location: String,
    val conditionScore: Int,
    val serialNumber: Int,
    val hasPairedDonor: Boolean,
    val status: RecipientStatus,
    val registeredBy: String,
    val registrationTime: Instant
) {
    companion object {
        fun from(s: RecipientState) = RecipientResponse(
            linearId         = s.linearId.toString(),
            bloodType        = s.bloodType,
            organNeeded      = s.organNeeded,
            age              = s.age,
            location         = s.location,
            conditionScore   = s.conditionScore,
            serialNumber     = s.serialNumber,
            hasPairedDonor   = s.hasPairedDonor,
            status           = s.status,
            registeredBy     = s.registeredBy.name.organisation,
            registrationTime = s.registrationTime
        )
    }
}

data class MatchResponse(
    val linearId: String,
    val matchScore: Double,
    val crossMatchResult: CrossMatchResult,
    val organType: OrganType,
    val donorHospital: String,
    val recipientHospital: String,
    val status: MatchStatus,
    val matchedAt: Instant,
    val resolvedAt: Instant?,
    val rejectionReason: String?
) {
    companion object {
        fun from(s: MatchState) = MatchResponse(
            linearId          = s.linearId.toString(),
            matchScore        = s.matchScore,
            crossMatchResult  = s.crossMatchResult,
            organType         = s.organType,
            donorHospital     = s.donorHospital.name.organisation,
            recipientHospital = s.recipientHospital.name.organisation,
            status            = s.status,
            matchedAt         = s.matchedAt,
            resolvedAt        = s.resolvedAt,
            rejectionReason   = s.rejectionReason
        )
    }
}

data class TransportResponse(
    val linearId: String,
    val matchId: String,
    val organType: OrganType,
    val originHospital: String,
    val destinationHospital: String,
    val transporter: String,
    val viabilityWindowHours: Int,
    val status: TransportStatus,
    val dispatchTime: Instant,
    val deliveredAt: Instant?
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
