package vn.edu.ltm.common.protocol;

public enum MessageType {
    REGISTER,
    REGISTER_ACK,
    HEARTBEAT,
    METRICS,
    RISK_ALERT,
    RISK_RESOLVED,
    RESTORE_AVAILABLE,
    RESTORE_RESULT,
    RANGING_CAPABILITIES,
    RANGING_START,
    RANGING_RESULT,
    RANGING_ABORT,
    ERROR
}
