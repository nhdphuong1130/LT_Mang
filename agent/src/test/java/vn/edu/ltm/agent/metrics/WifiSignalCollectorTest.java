package vn.edu.ltm.agent.metrics;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

class WifiSignalCollectorTest {
    @Test
    void parsesLinuxProcNetWirelessDbm() {
        String procSample = """
                Inter-| sta-|   Quality        |   Discarded packets               | Missed | WE
                 face | tus | link level noise |  nwid  crypt   frag  retry   misc | beacon | 22
                wlp0s20f3: 0000   70.  -48.  -256        0      0      0      0    233        0
                """;
        OptionalDouble rssi = WifiSignalCollector.parseProcNetWireless(procSample);
        assertTrue(rssi.isPresent());
        assertEquals(-48.0, rssi.getAsDouble(), 0.01);
    }

    @Test
    void parsesWindowsNetshSignalPercentOrDbm() {
        String netshSample = """
                There is 1 interface on the system:
                    Name                   : Wi-Fi
                    State                  : connected
                    SSID                   : Classroom-5G
                    Signal                 : 82%
                """;
        OptionalDouble rssi = WifiSignalCollector.parseWindowsNetsh(netshSample);
        assertTrue(rssi.isPresent());
        assertEquals(-59.0, rssi.getAsDouble(), 0.01);
    }

    @Test
    void parsesVietnameseWindowsNetshSignalPercent() {
        String netshSample = """
                Có 1 giao diện trên hệ thống:
                    Tên                    : Wi-Fi
                    Trạng thái             : đã kết nối
                    SSID                   : Classroom-5G
                    Tín hiệu               : 90%
                """;

        OptionalDouble rssi = WifiSignalCollector.parseWindowsNetsh(netshSample);

        assertTrue(rssi.isPresent());
        assertEquals(-55.0, rssi.getAsDouble(), 0.01);
    }
}
