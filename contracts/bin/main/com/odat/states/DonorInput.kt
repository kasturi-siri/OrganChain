package com.odat.states   // already exists, already whitelisted

import com.odat.enums.BloodType
import com.odat.enums.OrganType
import net.corda.core.serialization.CordaSerializable

@CordaSerializable
data class DonorInput(
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