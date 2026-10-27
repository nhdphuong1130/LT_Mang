package vn.edu.ltm.server.metrics;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LinuxP2pStationCollectorTest {
    @Test void unchangedCachedScanIsNotRelabelledAsANewRadioSample() {
        var current = new AtomicReference<>(Instant.parse("2026-10-24T00:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return current.get(); }
        };
        var collector = new LinuxP2pStationCollector(command -> {
            if (command.equals(List.of("iw", "dev"))) return "";
            if (command.equals(List.of("nmcli", "-t", "-f", "BSSID,SIGNAL,SSID",
                    "device", "wifi", "list", "--rescan", "no"))) {
                return "3A\\:7A\\:0E\\:A8\\:91\\:A7:92:DESKTOP-K5PKS87 9116";
            }
            return "";
        }, clock);

        var first = collector.collect().getFirst();
        current.set(current.get().plusSeconds(1));
        var repeated = collector.collect().getFirst();

        assertEquals(first.sampledAt(), repeated.sampledAt());
    }

    @Test void discoversOnlyRealP2pGroupInterfaces() {
        String iw = """
                phy#0
                \tInterface p2p-dev-wlp0s20f3
                \t\ttype P2P-device
                \tInterface wlp0s20f3
                \t\ttype managed
                \tInterface p2p-wlp0s20f3-0
                \t\ttype P2P-GO
                """;

        assertEquals(List.of("p2p-wlp0s20f3-0"), LinuxP2pStationCollector.parseGroupInterfaces(iw));
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

    @Test void parsesNmcliWifiDirectListAndCalculatesDbm() {
        String nmcli = """
                02\\:FB\\:C2\\:E6\\:39\\:4E:95:Ngan
                3A\\:7A\\:0E\\:A8\\:91\\:A7:89:DESKTOP-K5PKS87 9116
                """;
        Instant now = Instant.parse("2026-10-24T00:00:00Z");

        var samples = LinuxP2pStationCollector.parseNmcliWifiDirectList(nmcli, now);

        assertEquals(2, samples.size());
        assertEquals("3A:7A:0E:A8:91:A7", samples.get(1).stationMac());
        assertEquals(-55.5, samples.get(1).rssiDbm());
        assertEquals("p2p-wlp0s20f3-0", samples.get(1).interfaceName());
        assertEquals(now, samples.get(1).sampledAt());
    }
}
