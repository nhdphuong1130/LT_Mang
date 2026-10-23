package vn.edu.ltm.server.metrics;

import vn.edu.ltm.common.model.DirectLinkIdentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads RSSI observed by the Linux Server for peers connected to a real Wi-Fi Direct group. */
public final class LinuxP2pStationCollector {
    private static final Pattern STATION_BLOCK = Pattern.compile(
            "(?ms)^Station\\s+([0-9a-fA-F:-]{17})\\s+.*?\\R(.*?)(?=^Station|\\z)");
    private static final Pattern SIGNAL = Pattern.compile("(?m)^\\s*signal:\\s*(-?[0-9.]+)\\s*dBm");
    private static final Pattern SIGNAL_AVG = Pattern.compile("(?m)^\\s*signal avg:\\s*(-?[0-9.]+)\\s*dBm");

    @FunctionalInterface
    interface CommandRunner { String run(List<String> command) throws Exception; }

    public record StationSample(String interfaceName, String stationMac, double rssiDbm, Instant sampledAt) {
        public StationSample {
            interfaceName = interfaceName == null ? "" : interfaceName.strip();
            stationMac = DirectLinkIdentity.normalizeMac(stationMac);
            Objects.requireNonNull(sampledAt, "sampledAt");
            if (!isGroupInterface(interfaceName)) throw new IllegalArgumentException("Not a Wi-Fi Direct group interface");
            if (!Double.isFinite(rssiDbm)) throw new IllegalArgumentException("RSSI must be finite");
        }
    }

    private final CommandRunner runner;
    private final Clock clock;

    public LinuxP2pStationCollector() {
        this(LinuxP2pStationCollector::runCommand, Clock.systemUTC());
    }

    LinuxP2pStationCollector(CommandRunner runner, Clock clock) {
        this.runner = Objects.requireNonNull(runner);
        this.clock = Objects.requireNonNull(clock);
    }

    public List<StationSample> collect() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) return List.of();
        try {
            List<StationSample> result = new ArrayList<>();
            for (String iface : parseGroupInterfaces(runner.run(List.of("iw", "dev")))) {
                result.addAll(parseStationDump(iface,
                        runner.run(List.of("iw", "dev", iface, "station", "dump")), clock.instant()));
            }
            return List.copyOf(result);
        } catch (Exception unavailable) {
            return List.of();
        }
    }

    static List<String> parseGroupInterfaces(String output) {
        if (output == null || output.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        String candidate = null;
        for (String line : output.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.startsWith("Interface ")) {
                candidate = trimmed.substring("Interface ".length()).strip();
            } else if (candidate != null && trimmed.startsWith("type ")) {
                String type = trimmed.substring("type ".length()).strip();
                if ("P2P-GO".equalsIgnoreCase(type) && isGroupInterface(candidate)) result.add(candidate);
                candidate = null;
            }
        }
        return List.copyOf(result);
    }

    static List<StationSample> parseStationDump(String interfaceName, String output, Instant sampledAt) {
        if (!isGroupInterface(interfaceName) || output == null || output.isBlank()) return List.of();
        List<StationSample> result = new ArrayList<>();
        Matcher stationMatcher = STATION_BLOCK.matcher(output);
        while (stationMatcher.find()) {
            String block = stationMatcher.group(2);
            Matcher average = SIGNAL_AVG.matcher(block);
            Matcher current = SIGNAL.matcher(block);
            String value = average.find() ? average.group(1) : (current.find() ? current.group(1) : null);
            if (value != null) {
                try { result.add(new StationSample(interfaceName, stationMatcher.group(1), Double.parseDouble(value), sampledAt)); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        return List.copyOf(result);
    }

    static boolean isGroupInterface(String name) {
        return name != null && name.matches("p2p-.+-\\d+");
    }

    private static String runCommand(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean finished = process.waitFor(1, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("iw command timed out");
        }
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
