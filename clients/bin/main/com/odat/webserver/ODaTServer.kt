package com.odat.webserver

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

///*
// * ODaTServer — Spring Boot application entry point.
// *
// * Start with:
// *   cd clients && ../gradlew bootRun \
// *       --args='--config.rpc.host=localhost \
// *               --config.rpc.port=10003 \
// *               --config.rpc.username=hospitalA \
// *               --config.rpc.password=HospA@2024'
// *
// * The server connects to the Corda node at the specified RPC address
// * and exposes the ODaT REST API on port 8080 (configurable in application.properties).
// *
// * FIXES applied:
// *   Finding #1 — Removed the entire commented-out original class (lines 1–42).
// *                Dead commented code adds noise and creates confusion about which
// *                version is authoritative.
// *   Finding #2 — Removed the duplicate corsConfigurer() WebMvcConfigurer bean that
// *                was defined inline in this class. CorsConfig.kt already registers a
// *                CorsFilter bean covering /api/** with identical settings. Having two
// *                independent CORS mechanisms active simultaneously produces
// *                unpredictable behaviour — Spring applies both filter chains.
// *                CorsConfig.kt is the single authoritative CORS configuration.
//*/
@SpringBootApplication
class ODaTServer {

    companion object {

        /**
         * @JvmStatic instructs the Kotlin compiler to emit this as a real
         * public static void main(String[]) on the ODaTServer class itself,
         * not only as an instance method on the companion object.
         *
         * This is the only entry point Spring Boot's bootRun and java -jar
         * will find. Without @JvmStatic the method exists on
         * ODaTServer$Companion, which the JVM launcher does not check.
         */
        @JvmStatic
        fun main(args: Array<String>) {
            runApplication<ODaTServer>(*args)
        }
    }
}
