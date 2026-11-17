package com.med.icr.ui;

import com.med.icr.domain.Credential;
import com.med.icr.domain.CredentialType;
import com.med.icr.domain.FailureEvent;
import com.med.icr.pipeline.QueueWatermark;
import com.med.icr.topk.RotationPlanner;
import com.med.icr.util.Json;
import com.med.icr.util.Times;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** JavaFX 桌面入口：默认拉起三栏工作台，窗口关闭收进托盘继续订阅。 */
public final class DesktopApp extends Application {
    public static void launch(String[] args) {
        Application.launch(DesktopApp.class, args);
    }

    @Override
    public void start(Stage stage) {
        List<Credential> ledger = loadSampleLedger();
        List<RotationPlanner.RotationRow> planned = new RotationPlanner().plan(ledger, Instant.now(), 7);
        List<FailureEvent> events = loadSampleEvents();
        StatusBar status = new StatusBar(new QueueWatermark(200).observe(0, 0),
                planned.size(), 0, "本地样例（未接事件源）", "规则基线");
        MainWindow window = new MainWindow();
        BorderPane root = window.buildRoot(planned, events, null, status);
        Scene scene = new Scene(root, 1280, 800);
        String css = DesktopApp.class.getResource(AppTheme.STYLESHEET).toExternalForm();
        scene.getStylesheets().add(css);
        stage.setTitle("接口账号密钥轮换系统 · 信息科工作台");
        stage.setMinWidth(MainWindow.minimumWidth());
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> stage.hide()); // 收进托盘，不退出
        stage.show();

        TrayController tray = new TrayController();
        if (tray.supported()) {
            tray.install(() -> { }, () -> stage.show(), () -> {
                tray.release();
                javafx.application.Platform.exit();
            });
        }
    }

    private static List<Credential> loadSampleLedger() {
        Path file = Path.of("samples/credentials.json");
        if (!Files.exists(file)) return List.of();
        try {
            List<Credential> out = new ArrayList<>();
            var root = Json.MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            for (var n : root) {
                List<String> channels = new ArrayList<>();
                for (var c : n.path("channel_codes")) channels.add(c.asText());
                List<String> downstream = new ArrayList<>();
                for (var d : n.path("downstream_systems")) downstream.add(d.asText());
                String exp = n.path("expires_at").asText(null);
                out.add(new Credential(n.path("credential_id").asText(), n.path("channel_code").asText(),
                        CredentialType.parse(n.path("credential_type").asText()),
                        exp == null ? null : Times.parse(exp), n.path("owner_dept").asText(""),
                        n.path("owner").asText(""), n.path("rotation_entry").asText(""), null,
                        n.path("fingerprint").asText(null), null, channels, downstream));
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<FailureEvent> loadSampleEvents() {
        Path file = Path.of("samples/failures-sample.jsonl");
        if (!Files.exists(file)) return List.of();
        try {
            return new com.med.icr.ingest.FailureEventParser().parse(file).events();
        } catch (IOException e) {
            return List.of();
        }
    }
}
