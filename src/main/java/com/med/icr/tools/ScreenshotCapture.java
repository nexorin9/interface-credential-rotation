package com.med.icr.tools;

import com.med.icr.attribution.RuleAttributor;
import com.med.icr.domain.Credential;
import com.med.icr.domain.FailureEvent;
import com.med.icr.domain.RootCauseCard;
import com.med.icr.ingest.FailureEventParser;
import com.med.icr.pipeline.QueueWatermark;
import com.med.icr.topk.RotationPlanner;
import com.med.icr.ui.AppTheme;
import com.med.icr.ui.CredentialListPane;
import com.med.icr.ui.FailureTimelinePane;
import com.med.icr.ui.RootCauseCardPane;
import com.med.icr.ui.StatusBar;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** 真实 JavaFX 渲染截图：applyCss + layout 并等一个 pulse 后 snapshot，输出三个区域的 PNG。 */
public final class ScreenshotCapture extends Application {
    private static Path outDir = Path.of("docs/screenshots");

    public static void main(String[] args) {
        if (args.length > 0) outDir = Path.of(args[0]);
        Application.launch(ScreenshotCapture.class, args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        Files.createDirectories(outDir);
        List<Credential> ledger = SampleData.readLedger(Path.of("samples/credentials.json"));
        List<RotationPlanner.RotationRow> planned = new RotationPlanner()
                .plan(ledger, Instant.parse("2026-10-12T00:00:00Z"), 7);
        List<FailureEvent> events = new FailureEventParser().parse(Path.of("samples/failures-sample.jsonl")).events();
        List<RootCauseCard> cards = new RuleAttributor().attribute(events, ledger);

        write("01-expiry-list.png", expiryPane(planned));
        write("02-failure-timeline.png", timelinePane(events));
        RootCauseCardPane pane = new RootCauseCardPane();
        pane.setCard(cards.isEmpty() ? null : cards.get(0));
        write("03-root-cause-card.png", cardPane(pane.render()));
        System.out.println("JavaFX 真实渲染截图已写入 " + outDir);
        Platform.exit();
    }

    private static VBox expiryPane(List<RotationPlanner.RotationRow> planned) {
        TableView<CredentialListPane.Row> table = new TableView<>();
        TableColumn<CredentialListPane.Row, String> c1 = new TableColumn<>("凭据号");
        c1.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().credentialId()));
        TableColumn<CredentialListPane.Row, String> c2 = new TableColumn<>("剩余天数");
        c2.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().daysLeft()));
        TableColumn<CredentialListPane.Row, String> c3 = new TableColumn<>("通道");
        c3.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().channels()));
        TableColumn<CredentialListPane.Row, String> c4 = new TableColumn<>("下游系统");
        c4.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().downstream()));
        table.getColumns().addAll(c1, c2, c3, c4);
        table.getItems().addAll(CredentialListPane.rows(planned));
        table.setPrefHeight(240);
        return wrap("即将到期清单（真实渲染 · 数据来自合成样例）", table);
    }

    private static VBox timelinePane(List<FailureEvent> events) {
        VBox box = new VBox(AppTheme.GRID);
        List<FailureTimelinePane.TimelineRow> rows = FailureTimelinePane.rows(events);
        for (int i = 0; i < rows.size(); i++) {
            var r = rows.get(i);
            HBox line = new HBox(AppTheme.GRID);
            Circle dot = new Circle(4, Color.web(AppTheme.PRIMARY));
            line.getChildren().addAll(dot, new Label(r.time() + "  " + r.channel() + "  " + r.errorClass()
                    + "  " + r.maskedMessage()));
            box.getChildren().add(line);
            if (i < rows.size() - 1) {
                Line connector = new Line(4, 0, 4, 16);
                connector.setStroke(Color.web(AppTheme.TIMELINE_LINE));
                box.getChildren().add(connector);
            }
        }
        return wrap("失败事件时间轴（真实渲染 · 数据来自合成样例）", box);
    }

    private static VBox cardPane(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        return wrap("根因结论卡（真实渲染 · 含置信区间与降级标注）", label);
    }

    private static VBox wrap(String title, Region content) {
        VBox box = new VBox(AppTheme.CARD_GAP);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(AppTheme.CARD_PADDING));
        box.setPrefWidth(760);
        box.setPrefHeight(320);
        Label heading = new Label(title);
        heading.getStyleClass().add("column-title");
        box.getChildren().addAll(heading, content);
        VBox.setVgrow(content, Priority.ALWAYS);
        return box;
    }

    private static void write(String name, Region node) throws IOException, InterruptedException {
        Scene scene = new Scene(node);
        String css = ScreenshotCapture.class.getResource(AppTheme.STYLESHEET).toExternalForm();
        scene.getStylesheets().add(css);
        scene.getRoot().applyCss();
        node.applyCss();
        node.layout();
        // 等一个 pulse，避免截到空表体
        Thread.sleep(250);
        // 逐像素转换，避免额外引入 javafx.swing 模块
        WritableImage fxImage = node.snapshot(new javafx.scene.SnapshotParameters(), null);
        int w = (int) fxImage.getWidth();
        int h = (int) fxImage.getHeight();
        BufferedImage image = new BufferedImage(Math.max(w, 1), Math.max(h, 1), BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = fxImage.getPixelReader();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        ImageIO.write(image, "png", outDir.resolve(name).toFile());
    }
}
