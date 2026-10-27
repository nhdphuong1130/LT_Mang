package vn.edu.ltm.common.ranging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

/** Loads documented defaults and overlays an operator-owned properties file. */
public final class AcousticRangingConfigLoader {
    public static final String CONFIG_PATH_PROPERTY = "ltm.acoustic.config";
    private static final String DEFAULT_RESOURCE = "/acoustic-ranging-defaults.properties";
    private static final Set<String> KEYS = Set.of(
            "enabled", "playbackMixer", "captureMixer", "sampleRateHz", "chirpStartHz", "chirpEndHz",
            "playbackAmplitude",
            "chirpDurationMs", "captureDurationMs", "responseDelayMs", "measurementIntervalMs",
            "maxRetries", "rollingWindow", "confidenceThreshold", "minSnrDb", "minPeakRatio",
            "targetSnrDb", "targetPeakRatio", "maxTwoWayErrorSamples",
            "audibleCeilingHz", "maxAudibleLeakageDb", "maxDistanceMeters", "ambientTemperatureCelsius",
            "calibrationOffsetMillis", "maxJumpMeters", "snrWeight", "peakWeight", "twoWayWeight",
            "stabilityWeight", "hardwareWeight");
    private static final String PREFIX = "ranging.";

    public AcousticRangingConfig load() throws IOException {
        String configured = System.getProperty(CONFIG_PATH_PROPERTY);
        return load(configured == null || configured.isBlank() ? null : Path.of(configured));
    }

    public AcousticRangingConfig load(Path override) throws IOException {
        var values = new Properties();
        try (InputStream defaults = AcousticRangingConfigLoader.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (defaults == null) throw new IOException("Missing packaged acoustic ranging configuration");
            values.load(defaults);
        }
        if (override != null) {
            var supplied = new Properties();
            try (var reader = Files.newBufferedReader(override)) { supplied.load(reader); }
            validateKeys(supplied);
            values.putAll(supplied);
        }
        validateKeys(values);
        try {
            return AcousticRangingConfig.builder()
                    .enabled(bool(values, "enabled"))
                    .playbackMixer(text(values, "playbackMixer"))
                    .captureMixer(text(values, "captureMixer"))
                    .sampleRateHz(integer(values, "sampleRateHz"))
                    .chirpStartHz(decimal(values, "chirpStartHz"))
                    .chirpEndHz(decimal(values, "chirpEndHz"))
                    .playbackAmplitude(decimal(values, "playbackAmplitude"))
                    .chirpDurationMs(integer(values, "chirpDurationMs"))
                    .captureDurationMs(integer(values, "captureDurationMs"))
                    .responseDelayMs(integer(values, "responseDelayMs"))
                    .measurementIntervalMs(integer(values, "measurementIntervalMs"))
                    .maxRetries(integer(values, "maxRetries"))
                    .rollingWindow(integer(values, "rollingWindow"))
                    .confidenceThreshold(decimal(values, "confidenceThreshold"))
                    .minSnrDb(decimal(values, "minSnrDb"))
                    .minPeakRatio(decimal(values, "minPeakRatio"))
                    .targetSnrDb(decimal(values, "targetSnrDb"))
                    .targetPeakRatio(decimal(values, "targetPeakRatio"))
                    .maxTwoWayErrorSamples(decimal(values, "maxTwoWayErrorSamples"))
                    .audibleCeilingHz(decimal(values, "audibleCeilingHz"))
                    .maxAudibleLeakageDb(decimal(values, "maxAudibleLeakageDb"))
                    .maxDistanceMeters(decimal(values, "maxDistanceMeters"))
                    .ambientTemperatureCelsius(decimal(values, "ambientTemperatureCelsius"))
                    .calibrationOffsetMillis(decimal(values, "calibrationOffsetMillis"))
                    .maxJumpMeters(decimal(values, "maxJumpMeters"))
                    .snrWeight(decimal(values, "snrWeight"))
                    .peakWeight(decimal(values, "peakWeight"))
                    .twoWayWeight(decimal(values, "twoWayWeight"))
                    .stabilityWeight(decimal(values, "stabilityWeight"))
                    .hardwareWeight(decimal(values, "hardwareWeight"))
                    .build();
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Malformed acoustic ranging configuration", failure);
        }
    }

    private static void validateKeys(Properties values) {
        for (String property : values.stringPropertyNames()) {
            if (!property.startsWith(PREFIX) || !KEYS.contains(property.substring(PREFIX.length())))
                throw new IllegalArgumentException("Unknown acoustic ranging property: " + property);
        }
    }

    private static String text(Properties values, String key) {
        String value = values.getProperty(PREFIX + key);
        if (value == null) throw new IllegalArgumentException("Missing acoustic ranging property: " + PREFIX + key);
        return value.strip();
    }

    private static int integer(Properties values, String key) { return Integer.parseInt(text(values, key)); }
    private static double decimal(Properties values, String key) { return Double.parseDouble(text(values, key)); }
    private static boolean bool(Properties values, String key) {
        String value = text(values, key);
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
            throw new IllegalArgumentException("Malformed boolean property: " + PREFIX + key);
        return Boolean.parseBoolean(value);
    }
}
