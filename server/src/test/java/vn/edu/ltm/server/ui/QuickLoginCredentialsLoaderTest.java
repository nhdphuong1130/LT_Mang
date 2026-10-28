package vn.edu.ltm.server.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class QuickLoginCredentialsLoaderTest {
    @TempDir Path temp;

    @Test void loadsUsernameAndPasswordFromLocalPropertiesFile() throws Exception {
        Path file = temp.resolve("quick-login.properties");
        Files.writeString(file, "username=admin\npassword=local-secret-123\n");

        var credentials = new QuickLoginCredentialsLoader(file).load().orElseThrow();

        assertEquals("admin", credentials.username());
        assertEquals("local-secret-123", credentials.password());
    }

    @Test void missingFileProducesNoCredentials() throws Exception {
        assertTrue(new QuickLoginCredentialsLoader(temp.resolve("missing.properties")).load().isEmpty());
    }

    @Test void rejectsBlankValuesWithoutLeakingConfiguredPassword() throws Exception {
        Path file = temp.resolve("quick-login.properties");
        Files.writeString(file, "username=\npassword=must-not-appear-in-errors\n");

        var failure = assertThrows(IllegalArgumentException.class,
                () -> new QuickLoginCredentialsLoader(file).load());

        assertFalse(failure.getMessage().contains("must-not-appear-in-errors"));
    }
}
