package net.activitywatch.android.sync

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebhookTransportTest {
    @Test fun fallbackIsLimitedToTailscalePublicNames() {
        assertTrue(isFunnelHostname("device.tail123.ts.net"))
        assertFalse(isFunnelHostname("device.ts.net.attacker.example"))
        assertFalse(isFunnelHostname("private.example"))
    }
    @Test fun resolvesFreshPublicAnswersWithoutPinningAnAddress() {
        val result = publicFunnelAddresses(JSONObject("""{"Answer":[{"type":5,"data":"alias.example"},{"type":1,"data":"208.111.35.209"},{"type":1,"data":"208.111.34.11"}]}"""))
        assertEquals(listOf("208.111.35.209", "208.111.34.11"), result.map { it.hostAddress })
    }
    @Test fun rejectsPrivateFakeAndMalformedDnsAnswers() {
        val addresses = listOf("100.106.169.46", "198.18.0.107", "127.0.0.1", "192.168.31.32", "10.0.0.1", "172.19.0.1", "169.254.0.1", "224.0.0.1", "1.2.3.999", "1.2.3.example")
        for (address in addresses) {
            assertTrue(address, publicFunnelAddresses(JSONObject("""{"Answer":[{"type":1,"data":"$address"}]}""")).isEmpty())
        }
    }
}
