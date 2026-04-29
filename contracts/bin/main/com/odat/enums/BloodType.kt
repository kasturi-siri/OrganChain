package com.odat.enums

import net.corda.core.serialization.CordaSerializable

/**
 * FIX — Finding #9: Compatibility map was rebuilt inside isCompatibleWith() on
 * every call. During organ matching this method is invoked for every recipient in
 * the waiting list, so each call re-allocated an 8-entry MapOf from scratch.
 *
 * The map is now a companion object constant — allocated exactly once at class-load
 * time, shared across all callers for the lifetime of the JVM process.
 */
@CordaSerializable
enum class BloodType {
    O_POSITIVE, O_NEGATIVE,
    A_POSITIVE, A_NEGATIVE,
    B_POSITIVE, B_NEGATIVE,
    AB_POSITIVE, AB_NEGATIVE;

    companion object {
        /**
         * Donor blood type → list of compatible recipient blood types.
         * Allocated once; reused on every isCompatibleWith() call.
         */
        private val COMPATIBILITY: Map<BloodType, List<BloodType>> by lazy {
            mapOf(
                O_NEGATIVE  to values().toList(),                                               // universal donor
                O_POSITIVE  to listOf(O_POSITIVE, A_POSITIVE, B_POSITIVE, AB_POSITIVE),
                A_NEGATIVE  to listOf(A_NEGATIVE, A_POSITIVE, AB_NEGATIVE, AB_POSITIVE),
                A_POSITIVE  to listOf(A_POSITIVE, AB_POSITIVE),
                B_NEGATIVE  to listOf(B_NEGATIVE, B_POSITIVE, AB_NEGATIVE, AB_POSITIVE),
                B_POSITIVE  to listOf(B_POSITIVE, AB_POSITIVE),
                AB_NEGATIVE to listOf(AB_NEGATIVE, AB_POSITIVE),
                AB_POSITIVE to listOf(AB_POSITIVE)                                              // universal recipient
            )
        }
    }

    /**
     * Returns true if this (donor) blood type can donate to [recipient].
     * Delegates to the pre-built [COMPATIBILITY] map — O(1) lookup, zero allocation.
     */
    fun isCompatibleWith(recipient: BloodType): Boolean =
        COMPATIBILITY[this]?.contains(recipient) ?: false
}
