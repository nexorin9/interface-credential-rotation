package com.med.icr.ui;

import com.med.icr.domain.FailureEvent;
import com.med.icr.domain.RootCauseCard;
import com.med.icr.topk.RotationPlanner;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;

import java.util.List;

/** 三栏工作台：左=到期清单与通道状态，中=失败时间轴，右=根因结论卡与确认区。 */
public final class MainWindow {
    public record ColumnSpec(String id, String title, double minWidth, double prefWidth) {}

    public static final List<ColumnSpec> COLUMNS = List.of(
            new ColumnSpec("expiry", "即将到期清单与通道状态", 240, 320),
            new ColumnSpec("timeline", "失败时间轴", 260, 360),
            new ColumnSpec("card", "根因结论卡与确认区", 260, 420));

    public static double minimumWidth() {
        return COLUMNS.stream().mapToDouble(ColumnSpec::minWidth).sum();
    }

    /** 两档宽度（1280 / 768）下三栏都不塌陷、无横向滚动。 */
    public static boolean columnsFit(double width) {
        return width >= minimumWidth();
    }

    public BorderPane buildRoot(List<RotationPlanner.RotationRow> planned, List<FailureEvent> events,
                                RootCauseCard card, StatusBar statusBar) {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root");
        HBox columns = new HBox(AppTheme.CARD_GAP);
        columns.setPadding(new Insets(AppTheme.CARD_PADDING));

        TableView<CredentialListPane.Row> table = new TableView<>();
        table.getStyleClass().add("card");
        TableColumn<CredentialListPane.Row, String> id = new TableColumn<>("凭据号");
        id.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().credentialId()));
        TableColumn<CredentialListPane.Row, String> days = new TableColumn<>("剩余天数");
        days.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().daysLeft()));
        TableColumn<CredentialListPane.Row, String> channels = new TableColumn<>("通道");
        channels.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().channels()));
        TableColumn<CredentialListPane.Row, String> downstream = new TableColumn<>("下游系统");
        downstream.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().downstream()));
        table.getColumns().addAll(id, days, channels, downstream);
        table.getItems().addAll(CredentialListPane.rows(planned));
        table.setMinWidth(COLUMNS.get(0).minWidth());
        VBox left = card(COLUMNS.get(0).title(), table);

        VBox timelineBox = new VBox(AppTheme.GRID);
        List<FailureTimelinePane.TimelineRow> rows = FailureTimelinePane.rows(events);
        for (int i = 0; i < rows.size(); i++) {
            FailureTimelinePane.TimelineRow r = rows.get(i);
            HBox line = new HBox(AppTheme.GRID);
            Circle dot = new Circle(4, Color.web(AppTheme.PRIMARY));
            dot.getStyleClass().add("timeline-dot");
            Label text = new Label(r.time() + "  " + r.channel() + "  " + r.errorClass() + "  " + r.maskedMessage());
            line.getChildren().addAll(dot, text);
            timelineBox.getChildren().add(line);
            if (i < rows.size() - 1) {
                Line connector = new Line(4, 0, 4, AppTheme.ROW_HEIGHT - 8);
                connector.getStyleClass().add("timeline-line");
                timelineBox.getChildren().add(connector);
            }
        }
        ScrollPane timelineScroll = new ScrollPane(timelineBox);
        timelineScroll.setFitToWidth(true);
        timelineScroll.setMinWidth(COLUMNS.get(1).minWidth());
        VBox middle = card(COLUMNS.get(1).title(), timelineScroll);

        RootCauseCardPane pane = new RootCauseCardPane();
        pane.setCard(card);
        Label cardText = new Label(pane.render());
        cardText.setWrapText(true);
        ScrollPane cardScroll = new ScrollPane(cardText);
        cardScroll.setFitToWidth(true);
        cardScroll.setMinWidth(COLUMNS.get(2).minWidth());
        VBox right = card(COLUMNS.get(2).title(), cardScroll);

        for (VBox column : List.of(left, middle, right)) {
            column.setMinWidth(240);
            HBox.setHgrow(column, Priority.ALWAYS);
        }
        columns.getChildren().addAll(left, middle, right);
        root.setCenter(columns);
        Label status = new Label(statusBar.render());
        status.getStyleClass().add(statusBar.nearReject() ? "status-near-reject" : "status-bar");
        root.setBottom(status);
        return root;
    }

    private VBox card(String title, Region content) {
        VBox box = new VBox(AppTheme.GRID);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(AppTheme.CARD_PADDING));
        Label label = new Label(title);
        label.getStyleClass().add("column-title");
        box.getChildren().addAll(label, content);
        VBox.setVgrow(content, Priority.ALWAYS);
        return box;
    }
}
