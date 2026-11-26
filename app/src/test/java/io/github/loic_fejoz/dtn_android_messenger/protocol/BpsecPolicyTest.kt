package io.github.loic_fejoz.dtn_android_messenger.protocol

import io.github.loic_fejoz.dtn_android_messenger.data.model.BpsecKey
import io.github.loic_fejoz.dtn_android_messenger.data.model.LocalService
import io.github.loic_fejoz.dtn_android_messenger.data.model.ViewerType
import io.github.loic_fejoz.dtn_android_messenger.service.DtnEngineService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BpsecPolicyTest {
    @Test
    fun strictPolicyRejectsUnsignedBundlesFromUnknownSenders() {
        assertTrue(DtnEngineService.shouldRejectUnsignedBundle("strict"))
        assertFalse(DtnEngineService.shouldRejectUnsignedBundle("warn"))
        assertFalse(DtnEngineService.shouldRejectUnsignedBundle("none"))
    }

    @Test
    fun selectBpsecKeyIsAsymmetricAndUsesLongestPrefix() {
        val nodeKey = BpsecKey(nodeEid = "dtn://alice", secretKey = byteArrayOf(1))
        val serviceKey = BpsecKey(nodeEid = "dtn://alice/chat", secretKey = byteArrayOf(2))
        val bareKey = BpsecKey(nodeEid = "dtn://", secretKey = byteArrayOf(3))
        val keys = listOf(bareKey, nodeKey, serviceKey)

        // Longest prefix wins
        val matched = DtnEngineService.selectBpsecKey("dtn://alice/chat", keys)
        assertNotNull(matched)
        assertEquals("dtn://alice/chat", matched?.nodeEid)

        // Node key matches sub-service
        val nodeMatched = DtnEngineService.selectBpsecKey("dtn://alice/files", keys)
        assertEquals("dtn://alice", nodeMatched?.nodeEid)

        // Bare scheme EID in source must never match any key
        assertNull(DtnEngineService.selectBpsecKey("dtn://", keys))
        assertNull(DtnEngineService.selectBpsecKey("dtn://unknown/chat", keys))
    }

    @Test
    fun isLocalDestinationRejectsBareSchemeAndReversePrefix() {
        val services =
            listOf(
                LocalService("dtn://my-node/chat", "Chat", ViewerType.CHAT),
                LocalService("dtn://my-node/files", "Files", ViewerType.BUNDLE_LIST),
            )

        // Bare scheme must not match local node or services
        val (bareIsLocal, bareMatches) = DtnEngineService.isLocalDestination("dtn://", "dtn://my-node", services)
        assertFalse(bareIsLocal)
        assertTrue(bareMatches.isEmpty())

        // Short parent EID must not match specific service (reverse prefix)
        val (nodeIsLocal, nodeMatches) = DtnEngineService.isLocalDestination("dtn://my-node", "dtn://my-node", services)
        assertTrue(nodeIsLocal)
        assertTrue(nodeMatches.isEmpty())

        // Exact service match
        val (svcIsLocal, svcMatches) = DtnEngineService.isLocalDestination("dtn://my-node/chat", "dtn://my-node", services)
        assertTrue(svcIsLocal)
        assertEquals(listOf("dtn://my-node/chat"), svcMatches.map { it.serviceEid })
    }
}
