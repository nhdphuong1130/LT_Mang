package vn.edu.ltm.agent.config;

public record AgentConfig(String serverHost, int controlPort, String deviceId, String token) {
    public AgentConfig {
        if (serverHost == null || serverHost.isBlank()) throw new IllegalArgumentException("Server host is required");
        if (controlPort < 1 || controlPort > 65535) throw new IllegalArgumentException("Invalid control port");
        if (deviceId == null || !deviceId.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("Invalid device ID");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Device token is required");
    }

    @Override public String toString() {
        return "AgentConfig[serverHost=" + serverHost + ", controlPort=" + controlPort + ", deviceId=" + deviceId + ", token=<redacted>]";
    }
}
