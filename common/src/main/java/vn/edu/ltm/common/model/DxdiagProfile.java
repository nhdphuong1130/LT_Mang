package vn.edu.ltm.common.model;

public record DxdiagProfile(
        String computerName,
        String operatingSystem,
        String systemManufacturer,
        String systemModel,
        String biosVersion,
        String processor,
        String memory,
        String pageFileOrSwap,
        String directXOrKernelVersion,
        String gpuName,
        String gpuVendor,
        String gpuVram,
        String displayInfo,
        String diskSummary,
        String networkSummary
) {
    public static DxdiagProfile unknown(String deviceId, String platform) {
        return new DxdiagProfile(
                deviceId == null ? "Unknown" : deviceId,
                platform == null || platform.isBlank() ? "Unknown OS" : platform,
                "N/A", "N/A", "N/A", "N/A", "N/A", "N/A", "N/A",
                "N/A", "N/A", "N/A", "N/A", "N/A", "N/A"
        );
    }
}
