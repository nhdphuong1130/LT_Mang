package vn.edu.ltm.server.metrics;

import vn.edu.ltm.common.model.*;

import java.io.IOException;
import java.time.*;
import java.util.*;

/** Collects auditable, de-duplicated Server-side P2P RSSI samples at labelled distances. */
public final class DistanceCalibrationSession {
    public static final List<Double> REQUIRED_DISTANCES_METERS = List.of(0.5, 1.0, 2.0, 3.0, 5.0);
    private static final Duration MAX_SAMPLE_AGE = Duration.ofSeconds(5);

    public record AcceptedSample(String deviceId, double distanceMeters, double rssiDbm, Instant sampledAt,
                                 String interfaceName, String peerMac) { }

    private final String targetDeviceId;
    private final int samplesPerDistance;
    private final Clock clock;
    private final Map<Double, List<AcceptedSample>> samples = new LinkedHashMap<>();
    private final Set<String> seen = new HashSet<>();

    public DistanceCalibrationSession(String targetDeviceId) {
        this(targetDeviceId, 100, Clock.systemUTC());
    }

    DistanceCalibrationSession(String targetDeviceId, int samplesPerDistance, Clock clock) {
        this.targetDeviceId = Objects.requireNonNull(targetDeviceId).strip();
        if (this.targetDeviceId.isEmpty()) throw new IllegalArgumentException("Target device is required");
        if (samplesPerDistance <= 0) throw new IllegalArgumentException("Sample target must be positive");
        this.samplesPerDistance = samplesPerDistance;
        this.clock = Objects.requireNonNull(clock);
        REQUIRED_DISTANCES_METERS.forEach(distance -> samples.put(distance, new ArrayList<>()));
    }

    public synchronized boolean accept(String deviceId, double distanceMeters, DistanceTelemetry telemetry) {
        if (!targetDeviceId.equals(deviceId) || telemetry == null || !samples.containsKey(distanceMeters)) return false;
        if (!telemetry.hasPhysicalDistance()
                || !DistanceTelemetry.VERIFIED_DIRECT_SOURCE.equals(telemetry.measurementSource())
                || telemetry.signalSampledAt() == null
                || telemetry.directInterfaceName() == null
                || !LinuxP2pStationCollector.isGroupInterface(telemetry.directInterfaceName())) return false;
        if (Duration.between(telemetry.signalSampledAt(), clock.instant()).abs().compareTo(MAX_SAMPLE_AGE) > 0) return false;
        List<AcceptedSample> bucket = samples.get(distanceMeters);
        if (bucket.size() >= samplesPerDistance) return false;
        String key = telemetry.peerMac() + "|" + telemetry.signalSampledAt();
        if (!seen.add(key)) return false;
        bucket.add(new AcceptedSample(deviceId, distanceMeters, telemetry.rssiRawDbm(), telemetry.signalSampledAt(),
                telemetry.directInterfaceName(), telemetry.peerMac()));
        return true;
    }

    public synchronized int acceptedCount(double distanceMeters) {
        List<AcceptedSample> bucket = samples.get(distanceMeters);
        return bucket == null ? 0 : bucket.size();
    }

    public int samplesPerDistance() { return samplesPerDistance; }
    public String targetDeviceId() { return targetDeviceId; }

    public synchronized boolean complete() {
        return samples.values().stream().allMatch(values -> values.size() >= samplesPerDistance);
    }

    public synchronized DistanceCalibrationModel fit() {
        if (!complete()) throw new IllegalStateException("Chưa thu đủ mẫu tại tất cả khoảng cách");
        List<DistanceCalibrationPoint> points = samples.values().stream().flatMap(Collection::stream)
                .map(value -> new DistanceCalibrationPoint(value.distanceMeters(), value.rssiDbm())).toList();
        Instant now = clock.instant();
        return DistanceCalibrationModel.fit("wifi-direct-" + now.toEpochMilli(), now, points);
    }

    public synchronized DistanceCalibrationModel activate(DistanceCalibrationStore store) throws IOException {
        DistanceCalibrationModel model = fit();
        store.save(model);
        return model;
    }

    public synchronized String toCsv() {
        StringBuilder out = new StringBuilder("device_id,distance_m,rssi_dbm,sampled_at,interface_name,peer_mac\n");
        samples.values().stream().flatMap(Collection::stream).forEach(sample -> out
                .append(csv(sample.deviceId())).append(',').append(sample.distanceMeters()).append(',')
                .append(sample.rssiDbm()).append(',').append(sample.sampledAt()).append(',')
                .append(csv(sample.interfaceName())).append(',').append(csv(sample.peerMac())).append('\n'));
        return out.toString();
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}
