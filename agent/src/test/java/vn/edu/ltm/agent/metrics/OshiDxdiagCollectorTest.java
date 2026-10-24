package vn.edu.ltm.agent.metrics;

import org.junit.jupiter.api.Test;
import vn.edu.ltm.common.model.DxdiagProfile;

import static org.junit.jupiter.api.Assertions.*;

class OshiDxdiagCollectorTest {
    @Test
    void collectsRealHardwareProfileWithAllGpusAndUsagePercentages() {
        var collector = new OshiDxdiagCollector();
        DxdiagProfile profile = collector.collect();

        assertNotNull(profile);
        assertFalse(profile.computerName().isBlank());
        assertFalse(profile.operatingSystem().isBlank());
        assertFalse(profile.processor().isBlank());
        assertTrue(profile.memory().contains("%"), "Memory summary should include usage %: " + profile.memory());
        assertTrue(profile.diskSummary().contains("%"), "Disk summary should include usage %: " + profile.diskSummary());
        assertFalse(profile.networkSummary().contains("docker"), "Network summary should exclude docker bridge: " + profile.networkSummary());
        assertFalse(profile.networkSummary().contains("br-"), "Network summary should exclude br- bridge: " + profile.networkSummary());
    }
}
