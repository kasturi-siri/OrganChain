package com.odat.enums

import net.corda.core.serialization.CordaSerializable

@CordaSerializable
enum class BloodType {
    O_POSITIVE, O_NEGATIVE,
    A_POSITIVE, A_NEGATIVE,
    B_POSITIVE, B_NEGATIVE,
    AB_POSITIVE, AB_NEGATIVE;

    fun isCompatibleWith(recipient: BloodType): Boolean {
        val compatible = mapOf(
            O_NEGATIVE to values().toList(),
            O_POSITIVE to listOf(O_POSITIVE, A_POSITIVE, B_POSITIVE, AB_POSITIVE),
            A_NEGATIVE to listOf(A_NEGATIVE, A_POSITIVE, AB_NEGATIVE, AB_POSITIVE),
            A_POSITIVE to listOf(A_POSITIVE, AB_POSITIVE),
            B_NEGATIVE to listOf(B_NEGATIVE, B_POSITIVE, AB_NEGATIVE, AB_POSITIVE),
            B_POSITIVE to listOf(B_POSITIVE, AB_POSITIVE),
            AB_NEGATIVE to listOf(AB_NEGATIVE, AB_POSITIVE),
            AB_POSITIVE to listOf(AB_POSITIVE)
        )
        return compatible[this]?.contains(recipient) ?: false
    }
}