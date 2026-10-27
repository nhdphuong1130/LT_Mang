package vn.edu.ltm.server.ui;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class DashboardControllerSelectionTest {
    @Test void dashboardRefreshesDistanceAtLeastTwicePerSecond() {
        assertTrue(ServerDashboardApp.DASHBOARD_REFRESH_MILLIS <= 500);
    }
    @Test
    void clientCardKeepsEverySummaryLineReadableAndInspectorShowsFullMachineDetails() throws Exception {
        var error = new AtomicReference<Throwable>();
        var latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                var loader = new FXMLLoader(DashboardController.class.getResource("dashboard.fxml"));
                loader.load();
                DashboardController controller = loader.getController();
                var model = new DashboardViewModel(Runnable::run);
                model.login("admin");
                controller.configure(model, null, null, null, null, null, null, () -> { });

                var dxdiag = new vn.edu.ltm.common.model.DxdiagProfile(
                        "DESKTOP-CLIENT-WITH-LONG-NAME", "Windows 11 Pro 64-bit", "Actual manufacturer",
                        "Actual detailed laptop model", "BIOS", "CPU", "16384 MB RAM", "Page file",
                        "DirectX 12", "GPU", "Vendor", "VRAM", "1920x1080", "512 GB", "Wi-Fi");
                var distance = new vn.edu.ltm.common.model.DistanceTelemetry(
                        -58, -58, 84, 1.8, 2.4, 0.2, 128, 1,
                        "Rất gần (< 2m)", "Wi-Fi RSSI (1D Kalman Filter)");
                var sphere = new DashboardDataService.ClientSphereItem(
                        "pc-vanphong-client-long-id", "Máy văn phòng Bình", "Windows 11 Professional 64-bit",
                        true, false, true, true, false, 3, 56, 67, 48.0,
                        "CPU 3% RAM 56% Disk 67% Temp 48°C", dxdiag, distance);
                controller.applySnapshot(new DashboardDataService.Snapshot(
                        List.of("pc-vanphong-client-long-id — ONLINE"), List.of(), List.of(),
                        List.of(new DashboardDataService.DeviceBackupFolder(
                                "pc-vanphong-client-long-id", "Máy văn phòng Bình", List.of())),
                        List.of(sphere)));

                var flow = (FlowPane) loader.getNamespace().get("sphereContainer");
                var card = (VBox) flow.getChildren().getFirst();
                assertTrue(card.getPrefWidth() >= 310, "Card must be wide enough for full values");
                var detailLines = card.lookupAll(".client-detail-line");
                assertTrue(detailLines.size() >= 4, "Card should expose separate readable detail lines");
                for (var node : detailLines) {
                    var label = (Label) node;
                    assertTrue(label.isWrapText());
                    assertEquals(OverrunStyle.CLIP, label.getTextOverrun());
                }
                String cardText = detailLines.stream().map(node -> ((Label) node).getText())
                        .reduce("", (left, right) -> left + "\n" + right);
                assertTrue(cardText.contains("Disk 67%"));
                assertTrue(cardText.contains("~1.80 m"));

                var inspector = (TextArea) loader.getNamespace().get("dxdiagSummaryArea");
                assertTrue(inspector.getText().contains("Actual detailed laptop model"));
                assertTrue(inspector.getText().contains("RAM 56%"));
            } catch (Throwable failure) {
                error.set(failure);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError("Test failed on JavaFX thread", error.get());
    }

    @Test
    void dxdiagTextShowsOnlineClientWaitingForRssiInsteadOfFakeMetersOrOffline() {
        var item = new DashboardDataService.ClientSphereItem(
                "pc-b", "Binh", "Windows", true, false, true, true, false,
                10, 20, 30, null, "metrics",
                vn.edu.ltm.common.model.DxdiagProfile.unknown("pc-b", "Windows"),
                vn.edu.ltm.common.model.DistanceTelemetry.waitingForSignal());

        String text = DashboardController.formatDxdiagText(item);

        assertTrue(text.contains("Đang chờ định danh Wi-Fi Direct từ Agent"));
        assertFalse(text.contains("~-1.0 mét"));
        assertFalse(text.contains("Khoảng cách ước tính: OFFLINE"));
    }

    @Test void formatsTruthfulDirectLinkStatesAndLiteralPercent() {
        var waiting = vn.edu.ltm.common.model.DistanceTelemetry.waitingFor(
                vn.edu.ltm.common.model.DirectLinkStatus.WRONG_SIGNAL_SOURCE,
                "Server chưa thấy MAC Client trong nhóm Wi-Fi Direct");
        String details = DashboardController.formatDistanceDetails(waiting);
        assertTrue(details.contains("WRONG_SIGNAL_SOURCE"));
        assertTrue(details.contains("Server chưa thấy MAC Client"));
        assertTrue(details.contains("LAN RTT"));

        var item = new DashboardDataService.ClientSphereItem(
                "pc-b", "Binh", "Windows", true, false, true, true, false,
                10, 20, 30, null, "metrics",
                vn.edu.ltm.common.model.DxdiagProfile.unknown("pc-b", "Windows"), waiting);
        String network = assertDoesNotThrow(() -> DashboardController.formatNetworkDiagnostics(item));
        assertTrue(network.contains("Alert Status (>=80%)"));
    }

    @BeforeAll
    static void startJavaFx() throws Exception {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.startup(latch::countDown);
            latch.await(5, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyStarted) {
            // Toolkit already initialized
        }
    }

    @Test
    void periodicRefreshPreservesSelectedBackupFileAndRestoreButtonState() throws Exception {
        var error = new AtomicReference<Throwable>();
        var latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                var loader = new FXMLLoader(DashboardController.class.getResource("dashboard.fxml"));
                Parent root = loader.load();
                DashboardController controller = loader.getController();
                var model = new DashboardViewModel(Runnable::run);
                model.login("admin");

                controller.configure(model, null, null, null, null, null, null, () -> {});

                var v1 = new DashboardDataService.VersionRow(
                        50L, "pc-vanphong-T", "test.txt", Instant.parse("2026-10-23T07:09:01.504827253Z"), 7L);
                var snapshot = new DashboardDataService.Snapshot(
                        List.of("pc-vanphong-T — ONLINE — CPU - RAM - Disk -", "pc-vanphong-B — ONLINE — CPU - RAM - Disk -"),
                        List.of(),
                        List.of(v1),
                        List.of(
                                new DashboardDataService.DeviceBackupFolder("pc-vanphong-T", "May T", List.of("test.txt")),
                                new DashboardDataService.DeviceBackupFolder("pc-vanphong-B", "May B", List.of())
                        )
                );

                // First refresh loads the tree and list
                controller.applySnapshot(snapshot);

                @SuppressWarnings("unchecked")
                var backupTree = (TreeView<DashboardController.BackupTreeNode>) loader.getNamespace().get("backupTree");
                @SuppressWarnings("unchecked")
                var backupsList = (ListView<DashboardDataService.VersionRow>) loader.getNamespace().get("backupsList");
                var restoreButton = (Button) loader.getNamespace().get("restoreButton");

                // User selects pc-vanphong-T/test.txt in tree and selects the file version in backupsList
                var devNode = backupTree.getRoot().getChildren().get(1);
                var fileNode = devNode.getChildren().getFirst();
                backupTree.getSelectionModel().select(fileNode);
                backupsList.getSelectionModel().selectFirst();

                assertNotNull(backupsList.getSelectionModel().getSelectedItem());
                assertEquals(50L, backupsList.getSelectionModel().getSelectedItem().id());
                assertNotNull(model.selectedVersion());
                assertFalse(restoreButton.isDisabled(), "Restore button should be enabled when file is selected");

                // Simulate the 5-second Timeline auto-refresh with the same snapshot
                controller.applySnapshot(snapshot);

                // Selection MUST still be preserved after 5-second auto-refresh!
                assertNotNull(backupsList.getSelectionModel().getSelectedItem(),
                        "Selected file in backupsList must NOT be deselected after 5s refresh");
                assertEquals(50L, backupsList.getSelectionModel().getSelectedItem().id());
                assertNotNull(model.selectedVersion(),
                        "model.selectedVersion() must NOT be cleared after 5s refresh");
                assertFalse(restoreButton.isDisabled(),
                        "Restore button must remain enabled after 5s refresh");

                // Simulate another 5-second auto-refresh when a new backup version arrives
                var v2 = new DashboardDataService.VersionRow(
                        51L, "pc-vanphong-T", "test.txt", Instant.parse("2026-10-23T07:10:00Z"), 12L);
                var updatedSnapshot = new DashboardDataService.Snapshot(
                        snapshot.devices(),
                        snapshot.incidents(),
                        List.of(v2, v1),
                        List.of(
                                new DashboardDataService.DeviceBackupFolder("pc-vanphong-T", "May T", List.of("test.txt", "new.txt")),
                                new DashboardDataService.DeviceBackupFolder("pc-vanphong-B", "May B", List.of())
                        )
                );
                controller.applySnapshot(updatedSnapshot);

                assertNotNull(backupsList.getSelectionModel().getSelectedItem(),
                        "Selected file must remain selected even when tree and list are rebuilt with new backups");
                assertEquals(50L, backupsList.getSelectionModel().getSelectedItem().id());
                assertNotNull(model.selectedVersion());
                assertEquals(50L, model.selectedVersion().id());
                assertFalse(restoreButton.isDisabled());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError("Test failed on JavaFX thread", error.get());
        }
    }
}
