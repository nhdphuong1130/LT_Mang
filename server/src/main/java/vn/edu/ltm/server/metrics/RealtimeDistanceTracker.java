package vn.edu.ltm.server.metrics;

import vn.edu.ltm.common.model.DistanceTelemetry;
import vn.edu.ltm.common.model.DirectLinkStatus;
import vn.edu.ltm.common.model.KalmanDistanceEstimator;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks RSSI-derived physical estimates and independent ICMP/TCP RTT & TTL measurements per client.
 * RTT is never converted into metres.
 */
public final class RealtimeDistanceTracker {
    private static final Pattern PING_TIME = Pattern.compile("time[=<]([0-9.]+)\\s*ms", Pattern.CASE_INSENSITIVE);
    private static final Pattern PING_TTL = Pattern.compile("ttl=(\\d+)", Pattern.CASE_INSENSITIVE);

    public record PingResult(double rttMs, int ttl) { }

    private static final class DeviceTrack {
        final KalmanDistanceEstimator estimator = new KalmanDistanceEstimator();
        final AtomicBoolean probing = new AtomicBoolean(false);
        volatile DistanceTelemetry latestTelemetry;
        volatile long lastProbeNanos;
    }

    private final Map<String, DeviceTrack> tracks = new ConcurrentHashMap<>();
    private final DirectLinkRegistry directLinks;
    private final Supplier<List<LinuxP2pStationCollector.StationSample>> stationSupplier;
    private final Clock clock;
    private volatile List<LinuxP2pStationCollector.StationSample> stationSamples = List.of();
    private final AtomicBoolean collectingStations = new AtomicBoolean(false);
    private volatile long lastStationCollectionNanos;

    public RealtimeDistanceTracker() {
        this(new DirectLinkRegistry(), new LinuxP2pStationCollector()::collect, Clock.systemUTC());
    }

    public RealtimeDistanceTracker(DirectLinkRegistry directLinks,
                                   Supplier<List<LinuxP2pStationCollector.StationSample>> stationSupplier,
                                   Clock clock) {
        this.directLinks = Objects.requireNonNull(directLinks);
        this.stationSupplier = Objects.requireNonNull(stationSupplier);
        this.clock = Objects.requireNonNull(clock);
    }

    public void recordStationSamples(List<LinuxP2pStationCollector.StationSample> samples) {
        stationSamples = samples == null ? List.of() : List.copyOf(samples);
    }

    public DistanceTelemetry recordProbe(String deviceId, double rttMs, int ttl) {
        DeviceTrack track = tracks.computeIfAbsent(deviceId, id -> new DeviceTrack());
        var resolution = directLinks.resolve(deviceId, stationSamples);
        DistanceTelemetry updated;
        if (resolution.status() == DirectLinkStatus.CONNECTED && resolution.sample() != null) {
            var sample = resolution.sample();
            updated = verified(track.estimator.updateFromRssiAndRtt(sample.rssiDbm(), rttMs, ttl), sample);
        } else {
            updated = unavailable(track.estimator.updateFromRttOnly(rttMs, ttl), resolution);
        }
        track.latestTelemetry = updated;
        track.lastProbeNanos = System.nanoTime();
        return updated;
    }

    public DistanceTelemetry getOrProbe(String deviceId, String ipAddress, boolean online) {
        if (!online) {
            return DistanceTelemetry.offline();
        }
        DeviceTrack track = tracks.computeIfAbsent(deviceId, id -> new DeviceTrack());
        scheduleStationCollection();
        long now = System.nanoTime();
        if (ipAddress != null && !ipAddress.isBlank()
                && (track.latestTelemetry == null || (now - track.lastProbeNanos) > TimeUnit.SECONDS.toNanos(2))) {
            if (track.probing.compareAndSet(false, true)) {
                Thread.ofVirtual().name("distance-probe-" + deviceId).start(() -> {
                    try {
                        PingResult res = probeHost(ipAddress);
                        recordProbe(deviceId, res.rttMs(), res.ttl());
                    } finally {
                        track.probing.set(false);
                    }
                });
            }
        }
        if (track.latestTelemetry == null) {
            var resolution = directLinks.resolve(deviceId, stationSamples);
            return DistanceTelemetry.waitingFor(resolution.status(), resolution.reason());
        }
        return track.latestTelemetry;
    }

    private void scheduleStationCollection() {
        long now = System.nanoTime();
        if ((now - lastStationCollectionNanos) < TimeUnit.SECONDS.toNanos(1)) return;
        if (!collectingStations.compareAndSet(false, true)) return;
        lastStationCollectionNanos = now;
        Thread.ofVirtual().name("wifi-direct-station-scan").start(() -> {
            try { recordStationSamples(stationSupplier.get()); }
            catch (RuntimeException ignored) { }
            finally { collectingStations.set(false); }
        });
    }

    private static DistanceTelemetry verified(DistanceTelemetry base,
                                               LinuxP2pStationCollector.StationSample sample) {
        return new DistanceTelemetry(base.rssiRawDbm(), base.rssiFilteredDbm(), base.signalQualityPercent(),
                base.estimatedMeters(), base.rttMs(), base.jitterMs(), base.ttl(), base.hopCount(),
                base.zoneLabel(), base.method(), DistanceTelemetry.VERIFIED_DIRECT_SOURCE,
                DirectLinkStatus.CONNECTED, sample.interfaceName(), sample.stationMac(), sample.sampledAt(),
                base.calibrationId(), base.calibrated());
    }

    private static DistanceTelemetry unavailable(DistanceTelemetry base, DirectLinkRegistry.Resolution resolution) {
        return new DistanceTelemetry(-100, -100, 0, -1, base.rttMs(), base.jitterMs(), base.ttl(), base.hopCount(),
                "CHỜ LIÊN KẾT TRỰC TIẾP", resolution.reason(), "NONE", resolution.status(),
                "", "", null, "", false);
    }

    static PingResult probeHost(String ip) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        long startNanos = System.nanoTime();
        try {
            ProcessBuilder pb = isWindows
                    ? new ProcessBuilder("ping", "-n", "1", "-w", "900", ip)
                    : new ProcessBuilder("ping", "-c", "1", "-W", "1", ip);
            Process proc = pb.redirectErrorStream(true).start();
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            proc.waitFor(1200, TimeUnit.MILLISECONDS);
            PingResult parsed = parsePingOutput(out);
            if (parsed != null) return parsed;
        } catch (Exception ignored) { }
        double elapsedMs = Math.max(1.5, Math.min(45.0, (System.nanoTime() - startNanos) / 1_000_000.0));
        return new PingResult(elapsedMs, 128);
    }

    static PingResult parsePingOutput(String output) {
        if (output == null || output.isBlank()) return null;
        Matcher timeMatcher = PING_TIME.matcher(output);
        if (!timeMatcher.find()) return null;
        try {
            double rtt = Double.parseDouble(timeMatcher.group(1));
            int ttl = 128;
            Matcher ttlMatcher = PING_TTL.matcher(output);
            if (ttlMatcher.find()) {
                ttl = Integer.parseInt(ttlMatcher.group(1));
            }
            return new PingResult(rtt, ttl);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
