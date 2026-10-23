package vn.edu.ltm.agent.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import vn.edu.ltm.common.model.DirectLinkIdentity;
import vn.edu.ltm.common.model.DirectLinkStatus;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Reads only Windows Wi-Fi Direct virtual-adapter identity; RSSI is measured by the Linux Server. */
public final class DirectLinkIdentityCollector {
    public static final String CAPABILITY = "WIFI_DIRECT_IDENTITY_V1";
    public static final String DEFAULT_AGENT_VERSION = "1.1.0";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String POWERSHELL_QUERY = """
            $a=@(Get-NetAdapter -IncludeHidden | Where-Object {$_.InterfaceDescription -match 'Microsoft Wi-Fi Direct Virtual Adapter'} | Sort-Object @{Expression={if($_.Status -eq 'Up'){0}else{1}}},ifIndex)[0];
            [pscustomobject]@{supported=($null -ne $a);adapterName=$(if($null -ne $a){$a.Name}else{$null});status=$(if($null -ne $a){[string]$a.Status}else{'NotPresent'});macAddress=$(if($null -ne $a){$a.MacAddress}else{$null})}|ConvertTo-Json -Compress
            """;

    private final Clock clock;
    private final String osName;
    private final String agentVersion;

    public DirectLinkIdentityCollector() {
        this(Clock.systemUTC(), System.getProperty("os.name", ""),
                System.getProperty("ltm.agent.version", DEFAULT_AGENT_VERSION));
    }

    DirectLinkIdentityCollector(Clock clock, String osName, String agentVersion) {
        this.clock = clock;
        this.osName = osName == null ? "" : osName;
        this.agentVersion = agentVersion == null || agentVersion.isBlank() ? DEFAULT_AGENT_VERSION : agentVersion.strip();
    }

    public DirectLinkIdentity collect() {
        if (!osName.toLowerCase(Locale.ROOT).contains("win")) {
            return DirectLinkIdentity.unavailable(agentVersion, Set.of(CAPABILITY));
        }
        Process process = null;
        try {
            process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", POWERSHELL_QUERY)
                    .redirectErrorStream(true).start();
            boolean exited = process.waitFor(2, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return DirectLinkIdentity.unavailable(agentVersion, Set.of(CAPABILITY));
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return parseWindowsPowerShell(output, clock.instant(), agentVersion);
        } catch (Exception failure) {
            if (process != null) process.destroyForcibly();
            return DirectLinkIdentity.unavailable(agentVersion, Set.of(CAPABILITY));
        }
    }

    static DirectLinkIdentity parseWindowsPowerShell(String output, Instant sampledAt, String agentVersion) {
        try {
            var node = JSON.readTree(output);
            boolean supported = node.path("supported").asBoolean(false);
            String name = node.path("adapterName").asText("");
            String statusText = node.path("status").asText("NotPresent");
            String mac = node.path("macAddress").asText("");
            Set<String> capabilities = Set.of(CAPABILITY);
            if (!supported) return DirectLinkIdentity.unavailable(agentVersion, capabilities);
            DirectLinkStatus status = switch (statusText.toLowerCase(Locale.ROOT)) {
                case "up", "connected" -> DirectLinkStatus.CONNECTED;
                case "disconnected", "disabled" -> DirectLinkStatus.RECONNECTING;
                default -> DirectLinkStatus.UNAVAILABLE;
            };
            if (status == DirectLinkStatus.CONNECTED) {
                return DirectLinkIdentity.connected(name, mac, sampledAt, agentVersion, capabilities);
            }
            return new DirectLinkIdentity(status, name, mac, sampledAt, agentVersion, capabilities);
        } catch (Exception invalid) {
            return DirectLinkIdentity.unavailable(agentVersion, Set.of(CAPABILITY));
        }
    }
}
