package com.med.icr;

import com.med.icr.domain.Credential;
import com.med.icr.domain.ExpiryGrade;
import com.med.icr.domain.RootCauseCard;
import com.med.icr.pipeline.QueueWatermark;
import com.med.icr.topk.ExpiryListExporter;
import com.med.icr.topk.RotationPlanner;
import com.med.icr.topk.TopKFilter;
import com.med.icr.ui.AppTheme;
import com.med.icr.ui.MainWindow;
import com.med.icr.ui.RootCauseCardPane;
import com.med.icr.ui.SnapshotExporter;
import com.med.icr.ui.TrayController;
import com.med.icr.ui.UiState;
import com.med.icr.ui.UiStateController;
import com.med.icr.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** T32–T34 / T42–T46：TopK 剪枝、排期与导出、设计 token、三栏布局、确认闸门、三态与托盘导出。 */
final class TopKUiSuites {}

class TopKFilterTest {
    @Test void thresholdOnlyTightensNeverRelaxes() {
        TopKFilter f = new TopKFilter();
        f.updateThreshold(10);
        assertEquals(10, f.thresholdDays());
        f.updateThreshold(3);
        assertEquals(3, f.thresholdDays());
        f.updateThreshold(9);
        assertEquals(3, f.thresholdDays(), "反向输入不得放松阈值");
        assertTrue(f.mayQualify(3));
        assertTrue(f.mayQualify(0));
        assertFalse(f.mayQualify(4));
    }

    @Test void expiryWindowMustIntersectQueryWindow() {
        Instant now = Fixtures.T0;
        Instant queryEnd = now.plusSeconds(7L * 86400);
        TopKFilter f = new TopKFilter();
        f.updateThreshold(3);
        f.setWindow(now, queryEnd);
        assertTrue(f.mayQualifyRange(now, queryEnd, now.plusSeconds(3L * 86400)));
        assertFalse(f.mayQualifyRange(now, queryEnd, now.minusSeconds(86400L)));
        assertFalse(f.mayQualifyRange(now, queryEnd, now.plusSeconds(30L * 86400)));
        assertFalse(f.mayQualifyRange(now, queryEnd, null));
        assertTrue(f.mayQualifyRange(now, queryEnd), "两参重载只做区间相交判定");
    }
}

class RotationPlannerTest {
    @Test void sameDaysLeftSortsByAffectedChannelCountDesc() {
        Instant now = Fixtures.T0;
        Credential a = Fixtures.credential("CRED-A", "fp-a", now.plusSeconds(3L * 86400), "CH-01");
        Credential b = Fixtures.credential("CRED-B", "fp-b", now.plusSeconds(3L * 86400),
                "CH-01", "CH-02", "CH-03");
        Credential far = Fixtures.credential("CRED-C", "fp-c", now.plusSeconds(20L * 86400));
        RotationPlanner planner = new RotationPlanner();
        List<RotationPlanner.RotationRow> first = planner.plan(List.of(a, b, far), now, 7);
        assertEquals(2, first.size());
        assertEquals("CRED-B", first.get(0).credentialId());
        assertEquals(3, first.get(0).channelCount());
        assertEquals(3, first.get(0).daysLeft());
        List<RotationPlanner.RotationRow> second = planner.plan(List.of(a, b, far), now, 7);
        assertEquals(first.stream().map(RotationPlanner.RotationRow::credentialId).toList(),
                second.stream().map(RotationPlanner.RotationRow::credentialId).toList(),
                "两次运行次序一致");
    }

    @Test void gradeIsCarriedIntoRow() {
        Instant now = Fixtures.T0;
        Credential expiring = Fixtures.credential("CRED-X", "fp-x", now.plusSeconds(2L * 86400));
        List<RotationPlanner.RotationRow> rows = new RotationPlanner().plan(List.of(expiring), now, 7);
        assertEquals(1, rows.size());
        assertEquals(ExpiryGrade.ORANGE, rows.get(0).grade());
        assertEquals(2, rows.get(0).daysLeft());
    }
}

class ExpiryListExporterTest {
    @Test void headerIsFixedAndRowCountMatchesPlanner(@TempDir Path tmp) throws Exception {
        Instant now = Fixtures.T0;
        List<RotationPlanner.RotationRow> rows = new RotationPlanner().plan(List.of(
                Fixtures.credential("CRED-0007", "fp-7", now.plusSeconds(3L * 86400),
                        "CH-01", "CH-02", "CH-03"),
                Fixtures.credential("CRED-0003", "fp-3", now.plusSeconds(2L * 86400))), now, 7);
        String csv = ExpiryListExporter.toCsv(rows);
        assertEquals("凭据号,类型,剩余天数,通道,下游,责任科室,到期日", ExpiryListExporter.HEADER);
        assertEquals(ExpiryListExporter.HEADER, csv.split("\n")[0]);
        assertEquals(rows.size() + 1, csv.lines().count(), "导出行数 = 表头 + planner 入选数");
        Path out = tmp.resolve("expiry.csv");
        ExpiryListExporter.export(out, rows);
        assertTrue(Fixtures.read(out).startsWith("\uFEFF"));
        assertFalse(Fixtures.read(out).contains("RAW-SECRET"));
    }
}

class AppThemeTest {
    @Test void colorTokensSpacingAndControlStates() {
        assertEquals("#1F4E79", AppTheme.PRIMARY);
        assertEquals("#C0392B", AppTheme.ALERT_RED);
        assertEquals("#E67E22", AppTheme.WARNING_ORANGE);
        assertEquals("#27AE60", AppTheme.NORMAL_GREEN);
        assertEquals(8, AppTheme.GRID);
        assertEquals(16, AppTheme.CARD_PADDING);
        assertEquals(4, AppTheme.CONTROL_STATES.size());
        assertEquals(AppTheme.ALERT_RED, AppTheme.colorForGrade(ExpiryGrade.RED));
        assertEquals(AppTheme.WARNING_ORANGE, AppTheme.colorForGrade(ExpiryGrade.ORANGE));
    }

    @Test void stylesheetIsLoadableFromClasspath() {
        assertNotNull(AppTheme.class.getResource(AppTheme.STYLESHEET));
    }
}

class MainWindowLayoutTest {
    @Test void threeColumnsAreDeclaredAndFitAt768() {
        assertEquals(3, MainWindow.COLUMNS.size());
        assertTrue(MainWindow.COLUMNS.stream().allMatch(c -> c.id() != null && !c.title().isBlank()));
        assertEquals(List.of("expiry", "timeline", "card"),
                MainWindow.COLUMNS.stream().map(MainWindow.ColumnSpec::id).toList());
        assertEquals(760.0, MainWindow.minimumWidth());
        assertTrue(MainWindow.columnsFit(768), "768 宽下三栏不塌陷、无横向滚动");
        assertTrue(MainWindow.columnsFit(1280));
        assertFalse(MainWindow.columnsFit(600));
    }
}

class RootCauseCardPaneTest {
    private static RootCauseCard card() {
        return new RootCauseCard("证书过期", "CRED-0007", List.of("TRACE-1"), "换证",
                0.6, 0.8, "通道级", false, Fixtures.T0, 3, "CH-01 / CH-02");
    }

    @Test void unconfirmedCardSendsZeroRequests() {
        RootCauseCardPane pane = new RootCauseCardPane();
        AtomicInteger calls = new AtomicInteger();
        pane.setCard(card());
        pane.setDispatchPort(c -> { calls.incrementAndGet(); return true; });
        assertFalse(pane.dispatch());
        assertEquals(0, calls.get(), "未勾选确认时替身端点收到 0 次请求");
        assertTrue(pane.render().contains("已人工确认：否"));
    }

    @Test void secondConfirmationCancellationAlsoSendsZeroRequests() {
        RootCauseCardPane pane = new RootCauseCardPane();
        AtomicInteger calls = new AtomicInteger();
        pane.setCard(card());
        pane.setConfirmed(true);
        pane.setSecondConfirmation(() -> false);
        pane.setDispatchPort(c -> { calls.incrementAndGet(); return true; });
        assertFalse(pane.dispatch());
        assertEquals(0, calls.get());
    }

    @Test void bothConfirmationsAllowExactlyOneDispatch() {
        RootCauseCardPane pane = new RootCauseCardPane();
        AtomicInteger calls = new AtomicInteger();
        pane.setCard(card());
        pane.setConfirmed(true);
        pane.setSecondConfirmation(() -> true);
        pane.setDispatchPort(c -> { calls.incrementAndGet(); return true; });
        assertTrue(pane.dispatch());
        assertEquals(1, calls.get());
        assertTrue(pane.render().contains("本次未使用模型归因"));
    }
}

class UiStateTest {
    @Test void loadingEmptyAndErrorEachHaveTheirOwnBehavior() {
        UiStateController c = new UiStateController();
        c.setState(UiState.LOADING);
        assertTrue(c.buttonsDisabled());
        assertTrue(UiState.LOADING.description().contains("暂不可用"));
        c.setState(UiState.EMPTY);
        assertFalse(c.buttonsDisabled());
        assertTrue(c.emptyMessage("credential").contains("凭据台账"));
        c.setState(UiState.ERROR);
        assertTrue(c.errorMessage(UiStateController.ErrorKind.MODEL_UNAVAILABLE).contains("未使用模型归因"));
        assertTrue(c.errorMessage(UiStateController.ErrorKind.PROBE_FAILED).contains("已轮换"));
    }

    @Test void toastAndDestructiveConfirmation() {
        UiStateController c = new UiStateController();
        assertEquals("派单成功，处置单已写回", c.toast("ticket_ok"));
        assertEquals("派单成功，处置单已写回", c.lastToast());
        assertTrue(c.destructiveConfirmed(() -> true));
        assertFalse(c.destructiveConfirmed(() -> false));
        assertFalse(c.destructiveConfirmed(null));
        assertTrue(c.nearRejectStyle(new QueueWatermark(10).observe(8, 0)));
    }
}

class TrayControllerTest {
    @Test void unsupportedTrayDegradesWithoutThrowing() {
        TrayController tray = new TrayController(true);
        assertFalse(tray.supported());
        assertFalse(tray.install(() -> {}, () -> {}, () -> {}));
        assertTrue(tray.degradeToStatusBar().contains("托盘不可用"));
    }

    @Test void singleInstanceLockIsAcquiredAndReleased(@TempDir Path tmp) {
        TrayController tray = new TrayController(true);
        assertTrue(tray.acquireSingleInstance(tmp.resolve("icr-single.lock")));
        assertDoesNotThrow(tray::release);
    }
}

class SnapshotExporterTest {
    @Test void exportedCardAndCsvContainNoPlaintext(@TempDir Path tmp) throws Exception {
        RootCauseCard card = new RootCauseCard("token=RAW-SECRET-77777 导致失败", "CRED-0007",
                List.of("TRACE-1"), "换证", 0.5, 0.7, "通道级", false, Fixtures.T0, 1, "CH-01");
        assertFalse(SnapshotExporter.cardText(card).contains("RAW-SECRET-77777"));
        Path text = tmp.resolve("card.txt");
        SnapshotExporter.exportCardText(text, card);
        assertFalse(Fixtures.read(text).contains("RAW-SECRET-77777"));
        Path csv = tmp.resolve("expiry.csv");
        SnapshotExporter.exportExpiryCsv(csv, List.of());
        assertTrue(Files.exists(csv));
        assertEquals(ExpiryListExporter.HEADER, Fixtures.read(csv).replace("\uFEFF", "").trim());
    }
}
