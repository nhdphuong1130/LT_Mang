package vn.edu.ltm.agent.metrics;

import oshi.SystemInfo;
import vn.edu.ltm.common.model.DxdiagProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * Cross-platform hardware and system diagnostic collector inspired by Windows dxdiag.
 * Works on both Windows and Linux using OSHI and fast sysfs/env lookups.
 */
public final class OshiDxdiagCollector {
    private final SystemInfo system;
    private volatile StaticHardware cachedStatic;

    private record StaticHardware(
            String computerName,
            String osName,
            String manufacturer,
            String model,
            String bios,
            String cpuName,
            String directXOrKernel,
            String gpuName,
            String gpuVendor,
            String gpuVram,
            String displayInfo
    ) { }

    public OshiDxdiagCollector() {
        this(new SystemInfo());
    }

    public OshiDxdiagCollector(SystemInfo system) {
        this.system = system;
    }

    public DxdiagProfile collect() {
        try {
            var os = system.getOperatingSystem();
            var hw = system.getHardware();
            StaticHardware st = cachedStatic;
            if (st == null) {
                st = collectStatic(os, hw);
                cachedStatic = st;
            }

            var mem = hw.getMemory();
            var vm = mem.getVirtualMemory();
            long totalRam = mem.getTotal();
            long availRam = mem.getAvailable();
            long usedRam = Math.max(0L, totalRam - availRam);
            double ramPercent = totalRam > 0 ? (usedRam * 100.0 / totalRam) : 0.0;
            String memory = String.format(Locale.ROOT, "%s RAM — Đang dùng %.0f%% (%s used / %s free)",
                    formatMiB(totalRam), ramPercent, formatMiB(usedRam), formatMiB(availRam));
            String pageFile = formatMiB(vm.getSwapUsed()) + " used, " + formatMiB(vm.getSwapTotal()) + " total";

            var diskParts = new ArrayList<String>();
            for (var store : os.getFileSystem().getFileStores(true)) {
                String mount = safe(store.getMount(), "");
                String name = safe(store.getName(), mount);
                if ( isVirtualStore(mount, name) || store.getTotalSpace() <= 0) continue;
                long total = store.getTotalSpace();
                long free = store.getUsableSpace();
                long used = Math.max(0L, total - free);
                double pct = (used * 100.0) / total;
                String label = mount.isEmpty() || mount.equals(name) ? name : mount + " [" + name + "]";
                diskParts.add(String.format(Locale.ROOT, "%s (%s — Đang dùng %.0f%%, còn trống %s)",
                        label, formatGiB(total), pct, formatGiB(free)));
            }
            String diskSummary = diskParts.isEmpty() ? "N/A" : String.join("; ", diskParts);

            var netParts = new ArrayList<String>();
            for (var net : hw.getNetworkIFs()) {
                String ifName = safe(net.getName(), "");
                if (isVirtualInterface(ifName)) continue;
                String[] ipv4 = net.getIPv4addr();
                if (ipv4 != null && ipv4.length > 0) {
                    var validIps = new ArrayList<String>();
                    for (String ip : ipv4) {
                        if (!ip.startsWith("127.") && !ip.startsWith("169.254.")) validIps.add(ip);
                    }
                    if (!validIps.isEmpty()) {
                        netParts.add(net.getDisplayName() + " [IPv4: " + String.join(", ", validIps)
                                + ", MAC: " + safe(net.getMacaddr(), "N/A").toUpperCase(Locale.ROOT) + "]");
                    }
                }
            }
            String networkSummary = netParts.isEmpty() ? "Local LAN" : String.join("; ", netParts);

            return new DxdiagProfile(
                    st.computerName(), st.osName(), st.manufacturer(), st.model(), st.bios(),
                    st.cpuName(), memory, pageFile, st.directXOrKernel(),
                    st.gpuName(), st.gpuVendor(), st.gpuVram(), st.displayInfo(),
                    diskSummary, networkSummary
            );
        } catch (Throwable failure) {
            return DxdiagProfile.unknown(System.getProperty("user.name", "client"), System.getProperty("os.name", "OS"));
        }
    }

    private static StaticHardware collectStatic(oshi.software.os.OperatingSystem os, oshi.hardware.HardwareAbstractionLayer hw) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String computerName = resolveComputerName(os);
        String osName = safe(os.toString(), System.getProperty("os.name")) + " (" + os.getBitness() + "-bit)";

        String manufacturer = readDmi("sys_vendor");
        String model = readDmi("product_name");
        String biosVendor = readDmi("bios_vendor");
        String biosVersion = readDmi("bios_version");
        String biosDate = readDmi("bios_date");

        if (manufacturer == null || model == null || biosVersion == null) {
            var cs = hw.getComputerSystem();
            if (manufacturer == null) manufacturer = safe(cs.getManufacturer(), "Generic");
            if (model == null) model = safe(cs.getModel(), "Standard PC");
            if (biosVendor == null) biosVendor = safe(cs.getFirmware().getManufacturer(), "");
            if (biosVersion == null) biosVersion = safe(cs.getFirmware().getVersion(), "Default BIOS");
            if (biosDate == null) biosDate = safe(cs.getFirmware().getReleaseDate(), "N/A");
        }
        String bios = (safe(biosVendor, "") + " " + safe(biosVersion, "Default BIOS")
                + " (" + safe(biosDate, "N/A") + ")").trim();

        var cpu = hw.getProcessor();
        String cpuName = safe(cpu.getProcessorIdentifier().getName(), "CPU")
                + " (" + cpu.getPhysicalProcessorCount() + " Physical Cores, "
                + cpu.getLogicalProcessorCount() + " Logical CPUs)";
        long maxFreq = cpu.getMaxFreq();
        if (maxFreq > 0) {
            cpuName += String.format(Locale.ROOT, " @ %.2f GHz", maxFreq / 1_000_000_000.0);
        }

        String directXOrKernel = isWindows
                ? "DirectX 12 (Windows Build " + safe(os.getVersionInfo().getBuildNumber(), "N/A") + ")"
                : "Linux Kernel " + safe(os.getVersionInfo().getBuildNumber(), System.getProperty("os.version", "N/A"))
                  + " (OpenGL / Vulkan)";

        var gpuNames = new LinkedHashSet<String>();
        var gpuVendors = new LinkedHashSet<String>();
        var gpuVrams = new ArrayList<String>();
        int gpuCount = 0;
        if (!isWindows) {
            for (String line : readLinuxLspciGpus()) {
                gpuCount++;
                gpuNames.add(line);
                if (line.toLowerCase(Locale.ROOT).contains("nvidia")) gpuVendors.add("NVIDIA Corporation");
                else if (line.toLowerCase(Locale.ROOT).contains("intel")) gpuVendors.add("Intel Corporation");
                else if (line.toLowerCase(Locale.ROOT).contains("amd") || line.toLowerCase(Locale.ROOT).contains("ati")) gpuVendors.add("AMD / ATI");
            }
        }
        if (gpuNames.isEmpty()) {
            var gpus = hw.getGraphicsCards();
            gpuCount = gpus.size();
            for (var gpu : gpus) {
                String gName = safe(gpu.getName(), "");
                if (!gName.isEmpty()) gpuNames.add(gName);
                String gVendor = safe(gpu.getVendor(), "");
                if (!gVendor.isEmpty()) gpuVendors.add(gVendor);
                if (gpu.getVRam() > 0) gpuVrams.add(formatMiB(gpu.getVRam()));
            }
        }
        String gpuName = gpuNames.isEmpty() ? "Integrated / Standard Display Adapter" : String.join(" + ", gpuNames);
        String gpuVendor = gpuVendors.isEmpty() ? "Generic" : String.join(" / ", gpuVendors);
        String gpuVram = gpuVrams.isEmpty() ? "Shared / Dynamic VRAM" : String.join(" + ", gpuVrams);
        String displayInfo = "Arch: " + System.getProperty("os.arch", "x64")
                + " | GPUs detected: " + Math.max(1, gpuCount);

        return new StaticHardware(
                computerName, osName.trim(), manufacturer.trim(), model.trim(), bios,
                cpuName.trim(), directXOrKernel, gpuName, gpuVendor, gpuVram, displayInfo
        );
    }

    private static String resolveComputerName(oshi.software.os.OperatingSystem os) {
        String envComputer = System.getenv("COMPUTERNAME");
        if (envComputer != null && !envComputer.isBlank()) return envComputer.trim();
        String envHost = System.getenv("HOSTNAME");
        if (envHost != null && !envHost.isBlank()) return envHost.trim();
        String etcHostname = readFirstLine(Path.of("/etc/hostname"));
        if (etcHostname != null && !etcHostname.isBlank()) return etcHostname.trim();
        return safe(os.getNetworkParams().getHostName(), "Unknown-Host");
    }

    private static java.util.List<String> readLinuxLspciGpus() {
        var result = new ArrayList<String>();
        try {
            var proc = new ProcessBuilder("lspci").redirectErrorStream(true).start();
            String output = new String(proc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            for (String line : output.split("\\R")) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("vga compatible controller:") || lower.contains("3d controller:")
                        || lower.contains("display controller:")) {
                    int idx = line.indexOf(": ");
                    result.add(idx >= 0 ? line.substring(idx + 2).trim() : line.trim());
                }
            }
        } catch (Exception ignored) { }
        return result;
    }

    private static String readDmi(String file) {
        return readFirstLine(Path.of("/sys/devices/virtual/dmi/id", file));
    }

    private static String readFirstLine(Path path) {
        try {
            if (!Files.isReadable(path)) return null;
            String text = Files.readString(path).trim();
            return (text.isEmpty() || "unknown".equalsIgnoreCase(text)) ? null : text;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isVirtualStore(String mount, String name) {
        return mount.startsWith("/snap") || mount.startsWith("/run") || mount.startsWith("/sys")
                || mount.startsWith("/proc") || mount.startsWith("/dev") || mount.equals("/boot/efi")
                || name.startsWith("tmpfs") || name.startsWith("/dev/loop");
    }

    private static boolean isVirtualInterface(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("lo") || lower.startsWith("docker") || lower.startsWith("br-")
                || lower.startsWith("veth") || lower.startsWith("virbr") || lower.startsWith("vmnet")
                || lower.startsWith("vboxnet");
    }

    private static String safe(String value, String fallback) {
        return (value == null || value.isBlank() || "unknown".equalsIgnoreCase(value.trim())) ? fallback : value.trim();
    }

    private static String formatMiB(long bytes) {
        return String.format(Locale.ROOT, "%d MB (%.1f GB)", bytes / (1024 * 1024), bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static String formatGiB(long bytes) {
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
