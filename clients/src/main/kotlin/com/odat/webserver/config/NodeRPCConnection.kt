package com.odat.webserver.config

import net.corda.client.rpc.CordaRPCClient
import net.corda.client.rpc.CordaRPCConnection
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.utilities.NetworkHostAndPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy

/**
 * NodeRPCConnection — Spring-managed singleton that opens and maintains a
 * Corda RPC connection to the configured node.
 *
 * The [proxy] is the entry point for all flow invocations and vault queries
 * in the controller layer.
 *
 * Connection parameters come from application.properties (or env overrides):
 *   config.rpc.host     — node host
 *   config.rpc.port     — node RPC port
 *   config.rpc.username — RPC user
 *   config.rpc.password — RPC password
 */
@Component
open class NodeRPCConnection(
    @Value("\${config.rpc.host}")     private val host: String,
    @Value("\${config.rpc.port}")     private val rpcPort: Int,
    @Value("\${config.rpc.username}") private val username: String,
    @Value("\${config.rpc.password}") private val password: String
) {
    private lateinit var rpcConnection: CordaRPCConnection

    /** Corda RPC proxy — use this in controllers to start flows and query the vault. */
    lateinit var proxy: CordaRPCOps

    @PostConstruct
    open fun initialiseNodeRPCConnection() {
        val rpcAddress = NetworkHostAndPort(host, rpcPort)
        val rpcClient  = CordaRPCClient(rpcAddress)
        rpcConnection  = rpcClient.start(username, password)
        proxy          = rpcConnection.proxy
    }

    @PreDestroy
    open fun closeRpcConnection() {
        rpcConnection.notifyServerAndClose()
    }
}
