package vn.edu.ltm.common.ranging;

public enum RangingStatus {
    ACOUSTIC_READY,
    CONFIG_DISABLED,
    AUDIO_UNSUPPORTED,
    LOW_SNR,
    MULTIPATH_AMBIGUOUS,
    AUDIBLE_LEAKAGE,
    RANGING_TIMEOUT,
    WIFI_FALLBACK,
    VERIFYING,
    UNAVAILABLE
}
