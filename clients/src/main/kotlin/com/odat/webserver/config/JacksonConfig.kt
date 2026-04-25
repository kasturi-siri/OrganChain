package com.odat.webserver.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.odat.enums.BloodType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/**
 * JacksonConfig — customises the global Jackson ObjectMapper used by
 * Spring Boot's REST layer (request deserialization + response serialization).
 *
 * ═══════════════════════════════════════════════════════════════
 * BUG FIX — Missing JavaTimeModule (CRITICAL for UI date display)
 * ═══════════════════════════════════════════════════════════════
 * Original code registered only KotlinModule + BloodTypeDeserializer.
 * Jackson's default behaviour for java.time.Instant (used in
 * DonorState.registrationTime, MatchState.matchedAt, etc.) is to
 * serialize it as a JSON object:
 *
 *   { "epochSecond": 1714950000, "nano": 0 }
 *
 * The frontend's fmtDate(iso) calls new Date(iso), which returns
 * "Invalid Date" when given a plain JS object, breaking every date
 * column in the UI (donors, recipients, matches, transport,
 * audit trail, and viability timers).
 *
 * Fix:
 *   1. Register JavaTimeModule — teaches Jackson how to handle all
 *      java.time types (Instant, LocalDate, ZonedDateTime, …).
 *   2. Disable WRITE_DATES_AS_TIMESTAMPS — forces ISO-8601 string
 *      output, e.g. "2025-01-15T09:30:00Z", which new Date() and
 *      toLocaleDateString() parse correctly in every browser.
 * ═══════════════════════════════════════════════════════════════
 *
 * Required Gradle dependency (add to clients/build.gradle if not present):
 *   implementation "com.fasterxml.jackson.datatype:jackson-datatype-jsr310"
 *   (included transitively by spring-boot-starter-web in most setups)
 */
@Configuration
class JacksonConfig {

    @Bean
    fun objectMapper(): ObjectMapper {
        return ObjectMapper()
            .registerKotlinModule()              // handles Kotlin data classes / nullability
            .registerModule(JavaTimeModule())    // FIX: handles java.time.Instant etc.
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS) // FIX: ISO-8601 strings
            .registerModule(SimpleModule().apply {
                addDeserializer(BloodType::class.java, BloodTypeDeserializer())
            })
    }
}

/**
 * BloodTypeDeserializer — accepts both short aliases (O_POS, A_NEG …)
 * and canonical enum names (O_POSITIVE, A_NEGATIVE …) from the HTTP body.
 *
 * This makes the REST API forgiving: the UI sends canonical names
 * like "O_POSITIVE" (matching the <option> values in index.html) while
 * other clients can use the shorter "O_POS" form.
 */
class BloodTypeDeserializer : StdDeserializer<BloodType>(BloodType::class.java) {

    private val aliases = mapOf(
        "O_POS"  to BloodType.O_POSITIVE,
        "O_NEG"  to BloodType.O_NEGATIVE,
        "A_POS"  to BloodType.A_POSITIVE,
        "A_NEG"  to BloodType.A_NEGATIVE,
        "B_POS"  to BloodType.B_POSITIVE,
        "B_NEG"  to BloodType.B_NEGATIVE,
        "AB_POS" to BloodType.AB_POSITIVE,
        "AB_NEG" to BloodType.AB_NEGATIVE
    )

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): BloodType {
        val value = p.text.trim().uppercase()
        // Try alias map first, then fall back to direct enum name (O_POSITIVE, etc.)
        return aliases[value]
            ?: BloodType.valueOf(value)
    }
}
