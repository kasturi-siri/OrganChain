package com.odat.enums
import net.corda.core.serialization.CordaSerializable

/** Transplantable organ categories supported by ODaT. */
@CordaSerializable
enum class OrganType {
    KIDNEY,
    LIVER,
    HEART,
    LUNG,
    PANCREAS,
    CORNEA,
    SMALL_INTESTINE
}

/** Lifecycle states for a donor registration. */
@CordaSerializable
enum class DonorStatus {
    /** Organ is available for matching. */
    AVAILABLE,
    /** Organ has been matched and is reserved for a recipient. */
    ASSIGNED,
    /** Organ viability window has elapsed — no longer usable. */
    EXPIRED
}

/** Lifecycle states for a recipient registration. */
@CordaSerializable
enum class RecipientStatus {
    /** Patient is on the waitlist awaiting a match. */
    WAITING,
    /** A compatible donor has been matched — awaiting transplant. */
    MATCHED,
    /** Transplant completed successfully. */
    TRANSPLANTED,
    /** Patient removed from waitlist (e.g., deceased, withdrew). */
    REMOVED
}

/** Lifecycle states for a match record. */
@CordaSerializable
enum class MatchStatus {
    /** Match found by algorithm — awaiting admin confirmation. */
    PENDING_CONFIRMATION,
    /** Admin confirmed the match — triggers transport flow. */
    CONFIRMED,
    /** Match rejected (cross-match failure, admin override, etc.). */
    REJECTED
}

/** Lifecycle states for an organ transport assignment. */
@CordaSerializable
enum class TransportStatus {
    DISPATCHED,
    IN_TRANSIT,
    DELIVERED,
    FAILED
}

/** Result of the immunological cross-match test. */
@CordaSerializable
enum class CrossMatchResult {
    POSITIVE,   // compatible — transplant can proceed
    NEGATIVE    // incompatible — recipient disqualified
}
