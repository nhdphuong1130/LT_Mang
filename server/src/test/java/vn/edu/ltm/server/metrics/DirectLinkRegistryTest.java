package vn.edu.ltm.server.metrics;

import org.junit.jupiter.api.Test;
import vn.edu.ltm.common.model.DirectLinkIdentity;
import vn.edu.ltm.common.model.DirectLinkStatus;

import java.time.*;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DirectLinkRegistryTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test void matchesAgentIdentityToServerObservedStation() {
        var registry = new DirectLinkRegistry(clock);
        registry.update("pc-b", DirectLinkIdentity.connected("Local Area Connection* 1",
                "cc-c5-30-08-cf-12", NOW.minusSeconds(1), "1.1.0", Set.of("WIFI_DIRECT_IDENTITY_V1")));
        var sample = new LinuxP2pStationCollector.StationSample(
                "p2p-wlp0s20f3-0", "CC:C5:30:08:CF:12", -48, NOW);

        var result = registry.resolve("pc-b", List.of(sample));

        assertEquals(DirectLinkStatus.CONNECTED, result.status());
        assertEquals(sample, result.sample());
    }

    @Test void rejectsLegacyAgentAndInfrastructureSignal() {
        var registry = new DirectLinkRegistry(clock);
        assertEquals(DirectLinkStatus.AGENT_UPDATE_REQUIRED,
                registry.resolve("legacy", List.of()).status());

        registry.update("pc-b", DirectLinkIdentity.connected("Wi-Fi", "CC:C5:30:08:CF:12",
                NOW, "1.1.0", Set.of("WIFI_DIRECT_IDENTITY_V1")));
        assertEquals(DirectLinkStatus.WRONG_SIGNAL_SOURCE,
                registry.resolve("pc-b", List.of()).status());
    }

    @Test void exposesReconnectAndStaleStates() {
        var registry = new DirectLinkRegistry(clock);
        registry.update("pc-b", new DirectLinkIdentity(DirectLinkStatus.RECONNECTING, "", "", NOW,
                "1.1.0", Set.of("WIFI_DIRECT_IDENTITY_V1")));
        assertEquals(DirectLinkStatus.RECONNECTING, registry.resolve("pc-b", List.of()).status());

        registry.update("pc-b", DirectLinkIdentity.connected("Direct", "CC:C5:30:08:CF:12",
                NOW.minusSeconds(30), "1.1.0", Set.of("WIFI_DIRECT_IDENTITY_V1")));
        assertEquals(DirectLinkStatus.STALE_SIGNAL, registry.resolve("pc-b", List.of()).status());
    }
}
