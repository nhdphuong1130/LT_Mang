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
    ERROR
}
