package vn.edu.ltm.agent.alert;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import vn.edu.ltm.common.model.*;
import java.util.*;
import java.util.function.Consumer;

public final class JavaFxAlertSink implements AlertSink {
    private final Consumer<Runnable> dispatch;
    private final Consumer<RiskEvent> presenter;
    private final Map<RiskType, RiskEvent> active = new EnumMap<>(RiskType.class);

    public JavaFxAlertSink() { this(Platform::runLater, JavaFxAlertSink::present); }
    // Injection keeps domain tests headless; the default always marshals UI work to JavaFX.
    public JavaFxAlertSink(Consumer<Runnable> dispatch, Consumer<RiskEvent> presenter) {
        this.dispatch = Objects.requireNonNull(dispatch);
        this.presenter = Objects.requireNonNull(presenter);
    }

    @Override public synchronized void show(RiskEvent event) {
        if (event.status() == RiskEvent.Status.RESOLVED) { active.remove(event.riskType()); return; }
        if (active.putIfAbsent(event.riskType(), event) == null) {
            try { dispatch.accept(() -> presenter.accept(event)); }
            catch (RuntimeException failure) { active.remove(event.riskType()); throw failure; }
        }
    }

    private static void present(RiskEvent event) {
        var alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Resource warning");
        alert.setHeaderText(event.riskType().name().replace('_', ' '));
        alert.setContentText(event.message());
        alert.show();
    }
}
