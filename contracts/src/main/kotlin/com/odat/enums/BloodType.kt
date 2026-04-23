package com.odat.enums

/** All eight standard ABO/Rh blood types. */
enum class BloodType {
    A_POS, A_NEG,
    B_POS, B_NEG,
    O_POS, O_NEG,
    AB_POS, AB_NEG;

    /**
     * Standard ABO/Rh compatibility table.
     * Returns true if [this] donor blood type can donate to [recipient].
     */
    fun isCompatibleWith(recipient: BloodType): Boolean {
        val compatible: Map<BloodType, List<BloodType>> = mapOf(
            O_NEG  to values().toList(),                                                      // universal donor
            O_POS  to listOf(O_POS, A_POS, B_POS, AB_POS),
            A_NEG  to listOf(A_NEG, A_POS, AB_NEG, AB_POS),
            A_POS  to listOf(A_POS, AB_POS),
            B_NEG  to listOf(B_NEG, B_POS, AB_NEG, AB_POS),
            B_POS  to listOf(B_POS, AB_POS),
            AB_NEG to listOf(AB_NEG, AB_POS),
            AB_POS to listOf(AB_POS)
        )
        return compatible[this]?.contains(recipient) ?: false
    }
}
