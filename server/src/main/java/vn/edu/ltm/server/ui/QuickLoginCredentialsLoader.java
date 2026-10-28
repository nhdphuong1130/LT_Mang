package vn.edu.ltm.server.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

final class QuickLoginCredentialsLoader {
    static final String PATH_PROPERTY = "ltm.server.quickLoginFile";

    record Credentials(String username, String password) {
        Credentials {
            username = Objects.requireNonNull(username).trim();
            password = Objects.requireNonNull(password);
            if (username.isBlank() || password.isBlank()) {
                throw new IllegalArgumentException("Quick-login file must define non-blank username and password");
            }
        }
    }

    private final Path path;

    QuickLoginCredentialsLoader(Path path) {
        this.path = Objects.requireNonNull(path).toAbsolutePath().normalize();
    }

    static QuickLoginCredentialsLoader fromRuntimeConfiguration() {
        String configured = System.getProperty(PATH_PROPERTY, "").trim();
        Path path = configured.isEmpty()
                ? Path.of(System.getProperty("user.home"), ".ltm-server", "quick-login.properties")
                : Path.of(configured);
        return new QuickLoginCredentialsLoader(path);
    }

    Path path() { return path; }

    Optional<Credentials> load() throws IOException {
        if (!Files.isRegularFile(path)) return Optional.empty();
        var properties = new Properties();
        try (var input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return Optional.of(new Credentials(
                properties.getProperty("username", ""),
                properties.getProperty("password", "")));
    }
}
