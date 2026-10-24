package vn.edu.ltm.agent.metrics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cross-platform Wi-Fi RSSI (dBm) collector for Linux (/proc/net/wireless) and Windows (netsh wlan).
 */
public final class WifiSignalCollector {
    private static final Pattern NETSH_DBM = Pattern.compile("(-\\d+)\\s*dBm", Pattern.CASE_INSENSITIVE);
    private static final Pattern NETSH_SIGNAL_PCT = Pattern.compile(
            "(?:Signal|T[ií]n\\s*hi[eệ]u)\\s*:\\s*(\\d+)%",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public OptionalDouble collectRssiDbm() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (!isWindows) {
            try {
                Path wireless = Path.of("/proc/net/wireless");
                if (Files.isReadable(wireless)) {
                    OptionalDouble parsed = parseProcNetWireless(Files.readString(wireless, StandardCharsets.UTF_8));
                    if (parsed.isPresent()) return parsed;
                }
            } catch (Exception ignored) { }
        } else {
            try {
                var proc = new ProcessBuilder("netsh", "wlan", "show", "interfaces").redirectErrorStream(true).start();
                String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                proc.waitFor(1, TimeUnit.SECONDS);
                return parseWindowsNetsh(out);
            } catch (Exception ignored) { }
        }
        return OptionalDouble.empty();
    }

    static OptionalDouble parseProcNetWireless(String content) {
        if (content == null || content.isBlank()) return OptionalDouble.empty();
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("Inter-") || trimmed.startsWith("face") || !trimmed.contains(":")) {
                continue;
            }
            String afterColon = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            String[] parts = afterColon.split("\\s+");
            if (parts.length >= 3) {
                String levelToken = parts[2].endsWith(".") ? parts[2].substring(0, parts[2].length() - 1) : parts[2];
                try {
                    double val = Double.parseDouble(levelToken);
                    if (val < 0 && val >= -110) return OptionalDouble.of(val);
                } catch (NumberFormatException ignored) { }
            }
        }
        return OptionalDouble.empty();
    }

    static OptionalDouble parseWindowsNetsh(String content) {
        if (content == null || content.isBlank()) return OptionalDouble.empty();
        Matcher dbmMatcher = NETSH_DBM.matcher(content);
        if (dbmMatcher.find()) {
            try { return OptionalDouble.of(Double.parseDouble(dbmMatcher.group(1))); }
            catch (NumberFormatException ignored) { }
        }
        Matcher pctMatcher = NETSH_SIGNAL_PCT.matcher(content);
        if (pctMatcher.find()) {
            try {
                int pct = Integer.parseInt(pctMatcher.group(1));
                double dbm = (pct / 2.0) - 100.0;
                return OptionalDouble.of(dbm);
            } catch (NumberFormatException ignored) { }
        }
        return OptionalDouble.empty();
    }
}
