package vn.edu.ltm.agent.metrics;

import org.junit.jupiter.api.Test;
import vn.edu.ltm.common.model.DirectLinkStatus;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectLinkIdentityCollectorTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");

    @Test
    void parsesConnectedWindowsDirectAdapter() {
        String json = """
                {"supported":true,"adapterName":"Local Area Connection* 1","status":"Up","macAddress":"CC-C5-30-08-CF-12"}
                """;

        var identity = DirectLinkIdentityCollector.parseWindowsPowerShell(json, NOW, "1.1.0");

        assertEquals(DirectLinkStatus.CONNECTED, identity.status());
        assertEquals("Local Area Connection* 1", identity.interfaceName());
        assertEquals("CC:C5:30:08:CF:12", identity.localP2pMac());
        assertEquals(NOW, identity.sampledAt());
        assertTrue(identity.supports("WIFI_DIRECT_IDENTITY_V1"));
    }

    @Test
    void reportsReconnectStateForDisconnectedVirtualAdapter() {
        String json = """
                {"supported":true,"adapterName":"Local Area Connection* 2","status":"Disconnected","macAddress":"C2-C5-30-08-CF-12"}
                """;

        var identity = DirectLinkIdentityCollector.parseWindowsPowerShell(json, NOW, "1.1.0");

        assertEquals(DirectLinkStatus.RECONNECTING, identity.status());
        assertEquals("C2:C5:30:08:CF:12", identity.localP2pMac());
    }

    @Test
    void reportsUnavailableWhenWindowsHasNoDirectAdapter() {
        var identity = DirectLinkIdentityCollector.parseWindowsPowerShell(
                "{\"supported\":false,\"status\":\"NotPresent\"}", NOW, "1.1.0");

        assertEquals(DirectLinkStatus.UNAVAILABLE, identity.status());
        assertEquals("", identity.localP2pMac());
    }
}
