package vn.edu.ltm.common.model;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Agent-reported identity of its Wi-Fi Direct virtual adapter; it never contains router RSSI. */
public record DirectLinkIdentity(
        DirectLinkStatus status,
        String interfaceName,
        String localP2pMac,
        Instant sampledAt,
        String agentVersion,
        Set<String> capabilities
) {
    public DirectLinkIdentity {
        status = Objects.requireNonNull(status, "status");
        interfaceName = normalizeText(interfaceName);
        localP2pMac = normalizeMac(localP2pMac);
        agentVersion = normalizeText(agentVersion);
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        if (status == DirectLinkStatus.CONNECTED) {
            if (interfaceName.isEmpty()) throw new IllegalArgumentException("Connected direct link requires an interface");
            if (localP2pMac.isEmpty()) throw new IllegalArgumentException("Connected direct link requires a P2P MAC");
            if (sampledAt == null) throw new IllegalArgumentException("Connected direct link requires a sample time");
        }
    }

    public static DirectLinkIdentity connected(String interfaceName, String localP2pMac, Instant sampledAt,
                                               String agentVersion, Set<String> capabilities) {
        return new DirectLinkIdentity(DirectLinkStatus.CONNECTED, interfaceName, localP2pMac,
                sampledAt, agentVersion, capabilities);
    }

    public static DirectLinkIdentity unavailable(String agentVersion, Set<String> capabilities) {
        return new DirectLinkIdentity(DirectLinkStatus.UNAVAILABLE, "", "", null, agentVersion, capabilities);
    }

    public boolean supports(String capability) {
        return capability != null && capabilities.contains(capability);
    }

    public static String normalizeMac(String value) {
        String text = normalizeText(value);
        if (text.isEmpty()) return "";
        String hex = text.replace(":", "").replace("-", "").toUpperCase(Locale.ROOT);
        if (!hex.matches("[0-9A-F]{12}")) throw new IllegalArgumentException("Invalid MAC address");
        return String.join(":",
                hex.substring(0, 2), hex.substring(2, 4), hex.substring(4, 6),
                hex.substring(6, 8), hex.substring(8, 10), hex.substring(10, 12));
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.strip();
    }
}
