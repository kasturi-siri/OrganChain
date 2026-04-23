package com.odat.webserver

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * ODaTServer — Spring Boot application entry point.
 *
 * Start with:
 *   cd clients && ../gradlew bootRun
 *       --args='--config.rpc.host=localhost
 *               --config.rpc.port=10003
 *               --config.rpc.username=hospitalA
 *               --config.rpc.password=HospA@2024'
 *
 * The server connects to the Corda node at the specified RPC address
 * and exposes the ODaT REST API on port 8080 (configurable in application.properties).
 */
@SpringBootApplication
class ODaTServer {

    /**
     * CORS configuration — allow all origins in development.
     * In production restrict to the known frontend origin.
     */
    @Bean
    fun corsConfigurer(): WebMvcConfigurer = object : WebMvcConfigurer {
        override fun addCorsMappings(registry: CorsRegistry) {
            registry.addMapping("/api/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
        }
    }
}

fun main(args: Array<String>) {
    runApplication<ODaTServer>(*args)
}
