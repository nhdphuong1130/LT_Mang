package vn.edu.ltm.server;

import org.junit.jupiter.api.Assumptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TestCapabilities {
    private TestCapabilities() { }
    public static void assumeSymlinks(Path directory) throws IOException {
        Path probe = directory.resolve(".symlink-probe");
        try {
            Files.createSymbolicLink(probe, directory);
        } catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            Assumptions.assumeTrue(false, "Symbolic links unavailable: " + unsupported.getClass().getSimpleName());
        } finally { Files.deleteIfExists(probe); }
    }
}
