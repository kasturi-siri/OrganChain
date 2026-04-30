package com.odat.states

import com.odat.enums.BloodType
import com.odat.enums.OrganType
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.serialization.CordaSerializable

/**
 * DecryptedData.kt — In-memory data transfer objects produced by the
 * MatchingAuthority when it decrypts DonorState and RecipientState fields.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ARCHITECTURE OVERVIEW
 * ─────────────────────────────────────────────────────────────────────────────
 * Ledger (encrypted)          MatchingAuthority JVM (plaintext, in-memory only)
 * ───────────────────         ─────────────────────────────────────────────────
 * DonorState                  DecryptedDonorData
 *  encryptedBloodType  ──decrypt──▶  bloodType: BloodType
 *  encryptedOrganType  ──decrypt──▶  organType: OrganType
 *  encryptedAge        ──decrypt──▶  age: Int
 *  encryptedWeightKg   ──decrypt──▶  weightKg: Double   ──▶ bmi (computed)
 *  encryptedHeightCm   ──decrypt──▶  heightCm: Double
 *  encryptedLocation   ──decrypt──▶  location: String
 *  encryptedIsDeceased ──decrypt──▶  isDeceased: Boolean
 *  encryptedName       ──decrypt──▶  name: String
 *  encryptedContact    ──decrypt──▶  contact: String
 *
 * These objects are created inside OrganMatchingFlow.decryptDonorState() /
 * decryptRecipientState() and exist only for the duration of the matching
 * algorithm. They are NEVER serialised to disk or transmitted in a state.
 *
 * MatchSummary is @CordaSerializable because it is sent over a FlowSession
 * (Corda P2P, TLS-secured) from MatchingAuthority to each hospital node
 * after a match is confirmed.  It is also returned via RPC to the REST layer.
 * ─────────────────────────────────────────────────────────────────────────────
 */

/**
 * Fully decrypted representation of a [DonorState].
 * Used exclusively by the MatchingAuthority during Algorithm 1 execution.
 */
data class DecryptedDonorData(
    val linearId:   UniqueIdentifier,
    val name:       String,
    val contact:    String,
    val bloodType:  BloodType,
    val organType:  OrganType,
    val age:        Int,
    val weightKg:   Double,
    val heightCm:   Double,
    val isDeceased: Boolean,
    val location:   String
) {
    /** BMI — computed here identically to DonorState.bmi. */
    val bmi: Double get() = weightKg / ((heightCm / 100.0) * (heightCm / 100.0))
}

/**
 * Fully decrypted representation of a [RecipientState].
 * Used exclusively by the MatchingAuthority during Algorithm 1 execution.
 */
data class DecryptedRecipientData(
    val linearId:       UniqueIdentifier,
    val name:           String,
    val contact:        String,
    val bloodType:      BloodType,
    val organNeeded:    OrganType,
    val age:            Int,
    val weightKg:       Double,
    val heightCm:       Double,
    val conditionScore: Int,
    val serialNumber:   Int,
    val hasPairedDonor: Boolean,
    val location:       String
) {
    val bmi: Double get() = weightKg / ((heightCm / 100.0) * (heightCm / 100.0))
}

/**
 * Post-match summary sent from MatchingAuthority to both hospitals after
 * a match is CONFIRMED.
 *
 * Transmitted over Corda's TLS-secured P2P channel — plaintext inside the
 * session is protected by transport-layer encryption.
 *
 * Contains only the information each party needs to proceed with the
 * transplantation:
 *  - Donor hospital receives → recipient's name, contact, blood type, organ needed, location
 *  - Recipient hospital receives → donor's name, contact, blood type, organ available, location
 *  - Both receive → the match score for their records
 *
 * @CordaSerializable required because this is sent across a FlowSession.
 */
@CordaSerializable
data class MatchSummary(
    // Donor information (visible to recipient hospital after match)
    val donorName:       String,
    val donorContact:    String,
    val donorBloodType:  String,
    val donorOrganType:  String,
    val donorLocation:   String,
    val donorIsDeceased: Boolean,

    // Recipient information (visible to donor hospital after match)
    val recipientName:       String,
    val recipientContact:    String,
    val recipientBloodType:  String,
    val recipientOrganType:  String,
    val recipientLocation:   String,
    val recipientCondition:  Int,

    // Match metadata
    val matchScore:    Double,
    val matchLinearId: String
)
