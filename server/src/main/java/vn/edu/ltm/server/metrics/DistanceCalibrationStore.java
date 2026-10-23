package vn.edu.ltm.server.metrics;

import vn.edu.ltm.common.model.DistanceCalibrationModel;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.Objects;
import java.util.Properties;

/** Atomically persists the active distance model and its evidence metrics. */
public final class DistanceCalibrationStore {
    private final Path file;
    private volatile DistanceCalibrationModel active;

    public DistanceCalibrationStore(Path file) {
        this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
        this.active = loadOrDefault();
    }

    public DistanceCalibrationModel activeModel() { return active; }

    public synchronized void save(DistanceCalibrationModel model) throws IOException {
        Objects.requireNonNull(model);
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Properties properties = new Properties();
        properties.setProperty("id", model.id());
        properties.setProperty("calibratedAt", model.calibratedAt().toString());
        properties.setProperty("referenceRssiAtOneMeter", Double.toString(model.referenceRssiAtOneMeter()));
        properties.setProperty("pathLossExponent", Double.toString(model.pathLossExponent()));
        properties.setProperty("sampleCount", Integer.toString(model.sampleCount()));
        properties.setProperty("maeMeters", Double.toString(model.maeMeters()));
        properties.setProperty("rmseMeters", Double.toString(model.rmseMeters()));
        Path temp = Files.createTempFile(parent, "distance-calibration-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(output, "Wi-Fi Direct distance calibration");
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            active = model;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private DistanceCalibrationModel loadOrDefault() {
        if (!Files.isRegularFile(file)) return DistanceCalibrationModel.uncalibratedDefault();
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            return new DistanceCalibrationModel(
                    required(properties, "id"), Instant.parse(required(properties, "calibratedAt")),
                    Double.parseDouble(required(properties, "referenceRssiAtOneMeter")),
                    Double.parseDouble(required(properties, "pathLossExponent")),
                    Integer.parseInt(required(properties, "sampleCount")),
                    Double.parseDouble(required(properties, "maeMeters")),
                    Double.parseDouble(required(properties, "rmseMeters")));
        } catch (Exception invalid) {
            return DistanceCalibrationModel.uncalibratedDefault();
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
}
