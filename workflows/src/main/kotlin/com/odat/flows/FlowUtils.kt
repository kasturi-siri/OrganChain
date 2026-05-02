package com.odat.flows

import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party

/**
 * FlowUtils — shared utilities available to every Corda flow in this CorDapp.
 *
 * Implemented as Kotlin extension functions on [FlowLogic] so callers can
 * invoke them as if they were ordinary member functions, with full access to
 * [FlowLogic.serviceHub], while avoiding the boilerplate of a common base class.
 *
 * FIXES:
 *   Finding #7 — identical `resolveParty()` was copy-pasted into four flow files:
 *     RegisterDonorFlow, RegisterRecipientFlow, OrganMatchingFlow, TransportFlow.
 *   Centralising here means any future change (e.g. improved error message, retry
 *   logic, caching) is made in exactly one place.
 */

/**
 * Resolve a Corda network participant by its X.500 distinguished name.
 *
 * @param x500  Full X.500 string, e.g. "O=AdminNode,L=Chennai,C=IN"
 * @return      The resolved [Party]
 * @throws [FlowException] if the name is not present in the network map
 */
internal fun FlowLogic<*>.resolveParty(x500: String): Party =
    serviceHub.networkMapCache
        .getPeerByLegalName(CordaX500Name.parse(x500))
        ?: throw FlowException("Party not on network map: $x500")
