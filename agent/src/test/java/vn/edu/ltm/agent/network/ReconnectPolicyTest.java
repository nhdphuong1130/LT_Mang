package vn.edu.ltm.agent.network;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ReconnectPolicyTest {
    @Test void delaysAreTwoFiveTenThenCappedAtThirtySeconds() {
        var policy = new ReconnectPolicy();
        for (int seconds : new int[]{2, 5, 10, 30, 30, 30}) {
            assertEquals(Duration.ofSeconds(seconds), policy.nextDelay());
        }
    }

    @Test void successfulRegistrationResetsBackoff() {
        var policy = new ReconnectPolicy();
        policy.nextDelay();
        policy.nextDelay();
        policy.reset();
        assertEquals(Duration.ofSeconds(2), policy.nextDelay());
    }
}
