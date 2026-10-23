package vn.edu.ltm.common.model;

/** Observable lifecycle state of the separate Server–Client Wi-Fi Direct link. */
public enum DirectLinkStatus {
    CONNECTED,
    PAIRING,
    RECONNECTING,
    UNAVAILABLE,
    WRONG_SIGNAL_SOURCE,
    STALE_SIGNAL,
    AGENT_UPDATE_REQUIRED
}
