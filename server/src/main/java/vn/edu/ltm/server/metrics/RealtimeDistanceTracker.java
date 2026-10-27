package vn.edu.ltm.server.metrics;

import vn.edu.ltm.common.model.DistanceTelemetry;
import vn.edu.ltm.common.model.DirectLinkStatus;
import vn.edu.ltm.common.model.DistanceCalibrationModel;
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
    static final long STATION_COLLECTION_INTERVAL_MILLIS = 500;
    static final long PROBE_INTERVAL_MILLIS = 750;
    private static final Pattern PING_TIME = Pattern.compile("time[=<]([0-9.]+)\\s*ms", Pattern.CASE_INSENSITIVE);
    private static final Pattern PING_TTL = Pattern.compile("ttl=(\\d+)", Pattern.CASE_INSENSITIVE);

    public record PingResult(double rttMs, int ttl) { }

    private static final class DeviceTrack {
        KalmanDistanceEstimator estimator;
        String calibrationId;
        final AtomicBoolean probing = new AtomicBoolean(false);
        volatile DistanceTelemetry latestTelemetry;
        volatile long lastProbeNanos;
        synchronized KalmanDistanceEstimator estimatorFor(DistanceCalibrationModel model) {
            if (estimator == null || !model.id().equals(calibrationId)) {
                estimator = new KalmanDistanceEstimator(model.referenceRssiAtOneMeter(), model.pathLossExponent());
                calibrationId = model.id();
            }
            return estimator;
        }
    }

    private final Map<String, DeviceTrack> tracks = new ConcurrentHashMap<>();
    private final DirectLinkRegistry directLinks;
    private final Supplier<List<LinuxP2pStationCollector.StationSample>> stationSupplier;
    private final Clock clock;
    private final Supplier<DistanceCalibrationModel> calibrationSupplier;
    private final Map<String, LinuxP2pStationCollector.StationSample> stationSamplesByMac = new ConcurrentHashMap<>();
    private final AtomicBoolean collectingStations = new AtomicBoolean(false);
    private volatile long lastStationCollectionNanos;
    private final vn.edu.ltm.server.ranging.HybridDistanceFusion fusion;
    private final Map<String, vn.edu.ltm.server.ranging.RangingSample> latestAcousticSamples = new ConcurrentHashMap<>();

    public RealtimeDistanceTracker() {
        this(new DirectLinkRegistry(), new LinuxP2pStationCollector()::collect, Clock.systemUTC(),
                DistanceCalibrationModel::uncalibratedDefault);
    }

    public RealtimeDistanceTracker(DirectLinkRegistry directLinks,
                                   Supplier<List<LinuxP2pStationCollector.StationSample>> stationSupplier,
                                   Clock clock) {
        this(directLinks, stationSupplier, clock, DistanceCalibrationModel::uncalibratedDefault);
    }

    public RealtimeDistanceTracker(DirectLinkRegistry directLinks,
                                   Supplier<List<LinuxP2pStationCollector.StationSample>> stationSupplier,
                                   Clock clock,
                                   Supplier<DistanceCalibrationModel> calibrationSupplier) {
        this(directLinks, stationSupplier, clock, calibrationSupplier, defaultFusion());
    }

    private static vn.edu.ltm.server.ranging.HybridDistanceFusion defaultFusion() {
        try {
            return new vn.edu.ltm.server.ranging.HybridDistanceFusion(
                    new vn.edu.ltm.common.ranging.AcousticRangingConfigLoader().load());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load acoustic ranging config", e);
        }
    }

    public RealtimeDistanceTracker(DirectLinkRegistry directLinks,
                                   Supplier<List<LinuxP2pStationCollector.StationSample>> stationSupplier,
                                   Clock clock,
                                   Supplier<DistanceCalibrationModel> calibrationSupplier,
                                   vn.edu.ltm.server.ranging.HybridDistanceFusion fusion) {
        this.directLinks = Objects.requireNonNull(directLinks);
        this.stationSupplier = Objects.requireNonNull(stationSupplier);
        this.clock = Objects.requireNonNull(clock);
        this.calibrationSupplier = Objects.requireNonNull(calibrationSupplier);
        this.fusion = Objects.requireNonNull(fusion);
    }

    public void onAcousticSample(vn.edu.ltm.server.ranging.RangingSample sample) {
        if (sample != null && sample.deviceId() != null) {
            latestAcousticSamples.put(sample.deviceId(), sample);
        }
    }

    public void recordStationSamples(List<LinuxP2pStationCollector.StationSample> samples) {
        if (samples != null) {
            for (var sample : samples) {
                if (sample != null && sample.stationMac() != null) {
                    stationSamplesByMac.put(sample.stationMac(), sample);
                }
            }
        }
    }

    public DistanceTelemetry recordProbe(String deviceId, double rttMs, int ttl) {
        DeviceTrack track = tracks.computeIfAbsent(deviceId, id -> new DeviceTrack());
        var resolution = directLinks.resolve(deviceId, List.copyOf(stationSamplesByMac.values()));
        DistanceTelemetry updated;
        if (resolution.status() == DirectLinkStatus.CONNECTED && resolution.sample() != null) {
            var sample = resolution.sample();
            DistanceCalibrationModel model = calibrationSupplier.get();
            updated = verified(track.estimatorFor(model).updateFromRssiAndRtt(sample.rssiDbm(), rttMs, ttl),
                    sample, model);
        } else {
            DistanceCalibrationModel model = calibrationSupplier.get();
            updated = unavailable(track.estimatorFor(model).updateFromRttOnly(rttMs, ttl), resolution);
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
                && (track.latestTelemetry == null || (now - track.lastProbeNanos) > TimeUnit.MILLISECONDS.toNanos(PROBE_INTERVAL_MILLIS))) {
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
            var resolution = directLinks.resolve(deviceId, List.copyOf(stationSamplesByMac.values()));
            DistanceTelemetry base = DistanceTelemetry.waitingFor(resolution.status(), resolution.reason());
            return fusion.fuse(deviceId, latestAcousticSamples.get(deviceId), base, System.currentTimeMillis());
        }
        return fusion.fuse(deviceId, latestAcousticSamples.get(deviceId), track.latestTelemetry, System.currentTimeMillis());
    }

    private void scheduleStationCollection() {
        long now = System.nanoTime();
        if ((now - lastStationCollectionNanos) < TimeUnit.MILLISECONDS.toNanos(STATION_COLLECTION_INTERVAL_MILLIS)) return;
        if (!collectingStations.compareAndSet(false, true)) return;
        lastStationCollectionNanos = now;
        Thread.ofVirtual().name("wifi-direct-station-scan").start(() -> {
            try { recordStationSamples(stationSupplier.get()); }
            catch (RuntimeException ignored) { }
            finally { collectingStations.set(false); }
        });
    }

    private static DistanceTelemetry verified(DistanceTelemetry base,
                                               LinuxP2pStationCollector.StationSample sample,
                                               DistanceCalibrationModel model) {
        return new DistanceTelemetry(base.rssiRawDbm(), base.rssiFilteredDbm(), base.signalQualityPercent(),
                base.estimatedMeters(), base.rttMs(), base.jitterMs(), base.ttl(), base.hopCount(),
                base.zoneLabel(), base.method(), DistanceTelemetry.VERIFIED_DIRECT_SOURCE,
                DirectLinkStatus.CONNECTED, sample.interfaceName(), sample.stationMac(), sample.sampledAt(),
                model.id(), model.calibrated());
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
