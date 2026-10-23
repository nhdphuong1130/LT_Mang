package vn.edu.ltm.common.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectLinkIdentityTest {
    @Test
    void connectedIdentityNormalizesWindowsMacAddress() {
        var identity = DirectLinkIdentity.connected(
                "Local Area Connection* 1", "cc-c5-30-08-cf-12",
                Instant.parse("2026-10-24T00:00:00Z"), "1.1.0",
                Set.of("WIFI_DIRECT_IDENTITY_V1"));

        assertEquals(DirectLinkStatus.CONNECTED, identity.status());
        assertEquals("CC:C5:30:08:CF:12", identity.localP2pMac());
        assertTrue(identity.capabilities().contains("WIFI_DIRECT_IDENTITY_V1"));
    }

    @Test
    void rejectsMalformedMacAddress() {
        assertThrows(IllegalArgumentException.class, () -> DirectLinkIdentity.connected(
                "p2p0", "not-a-mac", Instant.now(), "1.1.0", Set.of()));
    }

    @Test
    void connectedIdentityRequiresInterfaceMacAndTimestamp() {
        assertThrows(IllegalArgumentException.class, () -> DirectLinkIdentity.connected(
                "", "CC:C5:30:08:CF:12", Instant.now(), "1.1.0", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> DirectLinkIdentity.connected(
                "p2p0", "", Instant.now(), "1.1.0", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> DirectLinkIdentity.connected(
                "p2p0", "CC:C5:30:08:CF:12", null, "1.1.0", Set.of()));
    }

    @Test
    void unavailableIdentityDoesNotRequireAdapterDetails() {
        var identity = DirectLinkIdentity.unavailable("1.0.0", Set.of());

        assertEquals(DirectLinkStatus.UNAVAILABLE, identity.status());
        assertEquals("", identity.interfaceName());
        assertEquals("", identity.localP2pMac());
    }
}
