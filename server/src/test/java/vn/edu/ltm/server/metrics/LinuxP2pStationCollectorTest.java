package vn.edu.ltm.server.metrics;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LinuxP2pStationCollectorTest {
    @Test void discoversOnlyRealP2pGroupInterfaces() {
        String iw = """
                phy#0
                \tInterface p2p-dev-wlp0s20f3
                \t\ttype P2P-device
                \tInterface wlp0s20f3
                \t\ttype managed
                \tInterface p2p-wlp0s20f3-0
                \t\ttype P2P-GO
                \tInterface p2p-wlp0s20f3-1
                \t\ttype P2P-client
                """;

        assertEquals(List.of("p2p-wlp0s20f3-0", "p2p-wlp0s20f3-1"),
                LinuxP2pStationCollector.parseGroupInterfaces(iw));
    }

    @Test void parsesStationMacAndPrefersAverageSignal() {
        String dump = """
                Station cc:c5:30:08:cf:12 (on p2p-wlp0s20f3-0)
                \tinactive time:\t20 ms
                \tsignal:\t\t-47 dBm
                \tsignal avg:\t-51 dBm
                """;
        Instant now = Instant.parse("2026-10-24T00:00:00Z");

        var samples = LinuxP2pStationCollector.parseStationDump("p2p-wlp0s20f3-0", dump, now);

        assertEquals(1, samples.size());
        assertEquals("CC:C5:30:08:CF:12", samples.getFirst().stationMac());
        assertEquals(-51.0, samples.getFirst().rssiDbm());
        assertEquals(now, samples.getFirst().sampledAt());
    }

    @Test void infrastructureWifiScanCannotMasqueradeAsDirectLinkSignal() {
        var collector = new LinuxP2pStationCollector(command -> {
            if (command.equals(List.of("iw", "dev"))) return "";
            if (command.equals(List.of("nmcli", "-t", "-f", "BSSID,SIGNAL,SSID",
                    "device", "wifi", "list", "--rescan", "no"))) {
                return "02\\:FB\\:C2\\:E6\\:39\\:4E:95:Ngan";
            }
            return "";
        }, Clock.systemUTC());

        assertTrue(collector.collect().isEmpty());
    }
}
