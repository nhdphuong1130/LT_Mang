package vn.edu.ltm.server.ansible;

import vn.edu.ltm.server.device.Device;
import vn.edu.ltm.server.device.DeviceProvisioningService;
import vn.edu.ltm.server.restore.RestoreJob;
import vn.edu.ltm.server.restore.RestoreService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Executes the Ansible playbook (ansible/init_sync_file.yml) to initialize a canonical
 * synchronized text file and distributes it across all permitted LAN clients.
 */
public final class AnsibleSyncService {
    public static final String SYNC_FILE_NAME = "THONG_BAO_DONG_BO_LAN.txt";
    private final Path dataDir;
    private final Path playbookDir;
    private final DeviceProvisioningService provisioning;
    private final RestoreService restores;

    public record SyncOutcome(Path generatedFile, boolean ranAnsibleCli, List<RestoreJob> broadcastJobs) { }

    public AnsibleSyncService(Path dataDir, Path playbookDir,
                              DeviceProvisioningService provisioning, RestoreService restores) {
        this.dataDir = dataDir;
        this.playbookDir = playbookDir;
        this.provisioning = provisioning;
        this.restores = restores;
    }

    public SyncOutcome initializeAndSyncAllClients(String customContent, String adminUsername)
            throws IOException, SQLException {
        Path syncDir = dataDir.resolve("ansible-sync");
        Files.createDirectories(syncDir);
        Path generated = syncDir.resolve(SYNC_FILE_NAME);
        String message = (customContent == null || customContent.isBlank())
                ? "Tài liệu khởi tạo tự động bằng Ansible và đồng bộ xuống toàn bộ máy trạm Client (Windows & Linux)."
                : customContent.trim();

        boolean ranCli = tryRunAnsiblePlaybook(syncDir, message);
        if (!ranCli || !Files.isRegularFile(generated)) {
            String content = """
                    ====================================================================
                    HỆ THỐNG QUẢN LÝ & GIÁM SÁT PHÒNG MÁY NỘI BỘ LAN (NETOP SCHOOL STYLE)
                    FILE ĐỒNG BỘ KHỞI TẠO TỰ ĐỘNG BỞI ANSIBLE PLAYBOOK (init_sync_file.yml)
                    ====================================================================
                    Thời gian khởi tạo : %s
                    Playbook           : ansible/init_sync_file.yml
                    Inventory          : ansible/inventory.ini
                    Hệ điều hành hỗ trợ: Windows & Linux (Cross-Platform Agent + dxdiag)
                    Ngưỡng cảnh báo    : CPU / RAM / Disk >= 80%% & Kiểm tra File rủi ro
                    --------------------------------------------------------------------
                    NỘI DUNG ĐỒNG BỘ CHO TẤT CẢ CLIENT:
                    %s
                    ====================================================================
                    """.formatted(Instant.now(), message);
            Files.writeString(generated, content, StandardCharsets.UTF_8);
        }

        List<String> targetDevices = provisioning.findAllDevices().stream()
                .filter(d -> !d.isolated() && d.allowRestore())
                .map(Device::deviceId)
                .toList();
        List<RestoreJob> jobs = restores.broadcastServerLocalFile(generated, SYNC_FILE_NAME, targetDevices, adminUsername);
        return new SyncOutcome(generated, ranCli, jobs);
    }

    private boolean tryRunAnsiblePlaybook(Path syncDir, String message) {
        if (playbookDir == null) return false;
        Path inventory = playbookDir.resolve("inventory.ini");
        Path playbook = playbookDir.resolve("init_sync_file.yml");
        if (!Files.isRegularFile(inventory) || !Files.isRegularFile(playbook)) return false;
        try {
            Process process = new ProcessBuilder(
                    "ansible-playbook", "-i", inventory.toString(), playbook.toString(),
                    "-e", "sync_dir=" + syncDir.toAbsolutePath(),
                    "-e", "custom_message=" + message.replace("\"", "'")
            ).redirectErrorStream(true).start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (IOException | InterruptedException unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
