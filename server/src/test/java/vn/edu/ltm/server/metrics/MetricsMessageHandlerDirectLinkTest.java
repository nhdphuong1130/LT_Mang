package vn.edu.ltm.server.metrics;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import vn.edu.ltm.common.model.DirectLinkStatus;

import static org.junit.jupiter.api.Assertions.*;

class MetricsMessageHandlerDirectLinkTest {
    @Test void parsesVersionCapabilitiesAndNestedDirectLinkIdentity() {
        var payload = JsonNodeFactory.instance.objectNode().put("agentVersion", "1.1.0");
        payload.putArray("capabilities").add("WIFI_DIRECT_IDENTITY_V1");
        payload.putObject("directLink")
                .put("status", "CONNECTED")
                .put("interfaceName", "Local Area Connection* 1")
                .put("localP2pMac", "cc-c5-30-08-cf-12")
                .put("sampledAt", "2026-10-24T00:00:00Z");
        payload.put("rssiDbm", -20); // Legacy/router RSSI must not be part of the identity.

        var identity = MetricsMessageHandler.parseDirectLink(payload);

        assertEquals(DirectLinkStatus.CONNECTED, identity.status());
        assertEquals("CC:C5:30:08:CF:12", identity.localP2pMac());
        assertTrue(identity.supports("WIFI_DIRECT_IDENTITY_V1"));
    }

    @Test void treatsMissingDirectLinkAsLegacyAgent() {
        var identity = MetricsMessageHandler.parseDirectLink(JsonNodeFactory.instance.objectNode());
        assertEquals(DirectLinkStatus.UNAVAILABLE, identity.status());
        assertFalse(identity.supports("WIFI_DIRECT_IDENTITY_V1"));
    }
}
