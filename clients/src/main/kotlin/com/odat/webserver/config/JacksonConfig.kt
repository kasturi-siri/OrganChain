package com.odat.webserver.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.core.JsonParser
import com.odat.enums.BloodType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import com.fasterxml.jackson.module.kotlin.registerKotlinModule

@Configuration
class JacksonConfig {

    @Bean
    fun objectMapper(): ObjectMapper {
        return ObjectMapper()
            .registerKotlinModule()          // fixes ALL data classes
            .registerModule(SimpleModule().apply {
                addDeserializer(BloodType::class.java, BloodTypeDeserializer())
            })
    }
}

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
        // try alias map first, then direct enum name
        return aliases[value]
            ?: BloodType.valueOf(value)  // handles "O_POSITIVE" etc. directly
    }
}