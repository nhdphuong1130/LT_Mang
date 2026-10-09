package vn.edu.ltm.server.config;

public record ServerConfig(String bindAddress, int controlPort) {
    public ServerConfig {
        if (bindAddress == null || bindAddress.isBlank()) throw new IllegalArgumentException("Bind address is required");
        if (controlPort < 0 || controlPort > 65535) throw new IllegalArgumentException("Invalid control port");
    }

    public static ServerConfig defaults() { return new ServerConfig("0.0.0.0", 5050); }
}
