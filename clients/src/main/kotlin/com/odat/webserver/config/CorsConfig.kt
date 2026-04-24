package com.odat.webserver.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter

/**
 * CorsConfig — allows requests from:
 *  - http://localhost (any port) — for dev web servers
 *  - file:// — for opening index.html directly in a browser
 *  - null origin — some browsers send null for file:// requests
 */
@Configuration
open class CorsConfig {
    @Bean
    open fun corsFilter(): CorsFilter {
        val config = CorsConfiguration()
        config.allowedOriginPatterns = listOf("*")
        config.allowedMethods        = listOf("GET","POST","PUT","DELETE","OPTIONS","PATCH")
        config.allowedHeaders        = listOf("*")
        config.allowCredentials      = false
        config.maxAge                = 3600L

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/api/**", config)
        return CorsFilter(source)
    }
}
