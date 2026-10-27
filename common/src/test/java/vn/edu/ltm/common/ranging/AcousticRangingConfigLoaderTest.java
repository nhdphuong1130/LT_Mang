package vn.edu.ltm.common.ranging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AcousticRangingConfigLoaderTest {
    @TempDir Path temp;

    @Test void loadsPackagedConfiguration() throws Exception {
        var config = new AcousticRangingConfigLoader().load(null);
        assertEquals(96_000, config.sampleRateHz());
        assertTrue(config.chirpStartHz() > config.audibleCeilingHz());
    }

    @Test void externalFileOverridesPackagedValues() throws Exception {
        Path override = temp.resolve("ranging.properties");
        Files.writeString(override, "ranging.measurementIntervalMs=750\n");
        var config = new AcousticRangingConfigLoader().load(override);
        assertEquals(750, config.measurementIntervalMs());
        assertEquals(96_000, config.sampleRateHz());
    }

    @Test void rejectsUnknownKeysToCatchTypos() throws Exception {
        Path override = temp.resolve("ranging.properties");
        Files.writeString(override, "ranging.mesurementIntervalMs=750\n");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new AcousticRangingConfigLoader().load(override));
        assertTrue(failure.getMessage().contains("Unknown"));
    }

    @Test void rejectsMalformedValueWithoutSilentlyUsingDefault() throws Exception {
        Path override = temp.resolve("ranging.properties");
        Files.writeString(override, "ranging.sampleRateHz=fast\n");
        assertThrows(IllegalArgumentException.class,
                () -> new AcousticRangingConfigLoader().load(override));
    }

    @Test void systemPropertySelectsExternalConfiguration() throws Exception {
        Path override = temp.resolve("ranging.properties");
        Files.writeString(override, "ranging.maxRetries=4\n");
        String previous = System.getProperty(AcousticRangingConfigLoader.CONFIG_PATH_PROPERTY);
        try {
            System.setProperty(AcousticRangingConfigLoader.CONFIG_PATH_PROPERTY, override.toString());
            assertEquals(4, new AcousticRangingConfigLoader().load().maxRetries());
        } finally {
            if (previous == null) System.clearProperty(AcousticRangingConfigLoader.CONFIG_PATH_PROPERTY);
            else System.setProperty(AcousticRangingConfigLoader.CONFIG_PATH_PROPERTY, previous);
        }
    }
}
