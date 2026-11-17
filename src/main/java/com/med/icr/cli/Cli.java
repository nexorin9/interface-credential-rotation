package com.med.icr.cli;

import com.med.icr.adapter.CredentialLedgerClient;
import com.med.icr.adapter.HttpProbe;
import com.med.icr.adapter.ProbeResult;
import com.med.icr.adapter.TicketClient;
import com.med.icr.attribution.RuleAttributor;
import com.med.icr.config.AppConfig;
import com.med.icr.domain.Credential;
import com.med.icr.domain.FailureEvent;
import com.med.icr.domain.IdempotencyKey;
import com.med.icr.domain.RootCauseCard;
import com.med.icr.domain.Ticket;
import com.med.icr.domain.TicketState;
import com.med.icr.ingest.FailureIngestPipeline;
import com.med.icr.pipeline.BackpressureQueue;
import com.med.icr.pipeline.EventCounter;
import com.med.icr.pipeline.QueueWatermark;
import com.med.icr.store.AuditDao;
import com.med.icr.store.Database;
import com.med.icr.store.EventArchiveDao;
import com.med.icr.store.ModelCallDao;
import com.med.icr.tools.SampleData;
import com.med.icr.topk.ExpiryListExporter;
import com.med.icr.topk.RotationPlanner;
import com.med.icr.ui.CredentialListPane;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** 运维通道：与桌面共用 attribution / store / adapter / topk 业务层，不另写一套逻辑。 */
public final class Cli {
    private Cli() {}

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            com.med.icr.Main.printHelp(out);
            return 0;
        }
        String command = args[0];
        try {
            return switch (command) {
                case "doctor" -> doctor(args, out, err);
                case "scan" -> scan(args, out, err);
                case "probe" -> probe(args, out, err);
                case "ticket" -> ticket(args, out, err);
                case "audit" -> audit(args, out, err);
                case "sync" -> sync(args, out, err);
                case "attribution" -> attribution(args, out, err);
                default -> {
                    err.println("未知子命令: " + command);
                    err.println("可用子命令: " + String.join(" / ", com.med.icr.Main.COMMANDS));
                    yield 2;
                }
            };
        } catch (Exception e) {
            err.println("命令执行失败: " + e.getClass().getSimpleName() + " " + String.valueOf(e.getMessage()));
            return 1;
        }
    }

    /** 默认只检查变量齐全性，不发任何网络请求；--check-reachability 才做只读可达性探测。 */
    private static int doctor(String[] args, PrintStream out, PrintStream err) {
        var rows = AppConfig.describe(System.getenv());
        out.println("配置项                      是否设置   用途");
        for (var row : rows) out.println(row.render());
        long missing = rows.stream().filter(r -> !r.set && AppConfig.REQUIRED.contains(r.name)).count();
        out.println("必需项缺失数: " + missing);
        out.println("模型路径: " + (AppConfig.lenient(System.getenv()).modelConfigured()
                ? "已配置" : "未配置，归因走规则基线"));
        if (has(args, "--check-reachability")) {
            for (String key : List.of(AppConfig.ESB_BASE_URL, AppConfig.CREDENTIAL_BASE_URL, AppConfig.MODEL_BASE_URL)) {
                String url = System.getenv(key);
                if (url == null || url.isBlank()) { out.println(key + " 可达性: 未配置"); continue; }
                ProbeResult r = new HttpProbe().probe(url);
                out.println(key + " 可达性: " + (r.passed() ? "可达" : "不可达")
                        + "（" + r.code() + "，只读探测未发写请求）");
            }
        } else {
            out.println("端点可达性: 使用 --check-reachability 显式探测（默认不发任何网络请求）");
        }
        return 0;
    }

    private static int scan(String[] args, PrintStream out, PrintStream err) throws IOException {
        Instant now = optInstant(args, "--as-of", Instant.now());
        int warnDays = Integer.parseInt(opt(args, "--warn-days", "7"));
        List<Credential> ledger = loadLedger(err);
        List<RotationPlanner.RotationRow> rows = new RotationPlanner().plan(ledger, now, warnDays);
        out.print(CredentialListPane.renderText(rows));
        out.println("口径: 剩余天数升序 → 影响通道数降序 → 到期时间升序；共 " + rows.size() + " 条");
        String export = opt(args, "--export", null);
        if (export != null) {
            ExpiryListExporter.export(Path.of(export), rows);
            out.println("已导出: " + export);
        }
        return 0;
    }

    private static int probe(String[] args, PrintStream out, PrintStream err) {
        String endpoint = opt(args, "--endpoint", firstConfigured(
                AppConfig.CREDENTIAL_BASE_URL, AppConfig.ESB_BASE_URL));
        if (endpoint == null || endpoint.isBlank()) {
            out.println("连通性自检: 未通过");
            out.println("失败原因分类: NO_ENDPOINT_CONFIGURED");
            out.println("说明: 未配置端点，未发起真实探测；配置 CREDENTIAL_BASE_URL 或传 --endpoint 后重跑");
            return 0;
        }
        ProbeResult r = new HttpProbe().probe(endpoint);
        out.println("连通性自检: " + (r.passed() ? "通过" : "未通过"));
        out.println("端点: " + r.endpoint());
        out.println("失败原因分类: " + r.failureClass());
        out.println("错误码: " + r.code());
        out.println("探测时刻: " + r.probedAt());
        out.println("说明: " + r.detail() + "；探测只读，不推进凭据状态");
        return 0;
    }

    private static int ticket(String[] args, PrintStream out, PrintStream err) {
        if (!has(args, "--confirm")) {
            out.println("拒绝外发：ticket 子命令必须显式带 --confirm（与界面人工确认闸门同语义）");
            return 2;
        }
        String base = System.getenv(AppConfig.CREDENTIAL_BASE_URL);
        String token = System.getenv(AppConfig.CREDENTIAL_TOKEN);
        if (base == null || base.isBlank() || token == null || token.isBlank()) {
            out.println("台账未配置（CREDENTIAL_BASE_URL / CREDENTIAL_TOKEN），未发出任何写请求");
            return 0;
        }
        String credentialId = opt(args, "--credential-id", "CRED-0007");
        Instant window = optInstant(args, "--window-start", Instant.parse("2026-11-09T08:00:00Z"));
        Ticket draft = new Ticket(credentialId, "接口失败集中在该凭据上（人工确认后派单）",
                List.of("TRACE-A-000", "TRACE-A-001"), window.plusSeconds(86400 * 3),
                TicketState.PENDING, IdempotencyKey.compute(credentialId, window), null);
        var result = new TicketClient(base, token).dispatch(draft, window);
        if (result.success()) {
            out.println("派单成功，单号 " + result.ticketNo() + "，幂等键 " + result.idempotencyKey());
            return 0;
        }
        out.println("写回失败，可重试（本地状态保持未派发）：" + result.errorCode());
        return 1;
    }

    private static int audit(String[] args, PrintStream out, PrintStream err) throws IOException {
        try (Database db = Database.openDefault()) {
            AuditDao auditDao = new AuditDao(db);
            ModelCallDao modelDao = new ModelCallDao(db);
            out.println("审计记录 " + auditDao.count() + " 条 / 模型调用记录 " + modelDao.count() + " 条");
            for (AuditDao.Entry e : auditDao.all()) {
                out.println(e.occurredAt() + " | " + e.actor() + " | " + e.action()
                        + " | " + e.objectId() + " | " + e.result() + " | 理由 " + e.reason());
            }
            for (ModelCallDao.Entry e : modelDao.all()) {
                out.println(e.calledAt() + " | model=" + e.modelName() + " | host=" + e.baseUrlHost()
                        + " | 输入=" + e.inputEventCount() + " | 降级=" + e.degraded()
                        + " | 耗时=" + e.elapsedMillis() + "ms");
            }
            String export = opt(args, "--export", null);
            if (export != null) {
                StringBuilder sb = new StringBuilder("时间,操作人,动作,对象,结果,理由\n");
                for (AuditDao.Entry e : auditDao.all()) {
                    sb.append(e.occurredAt()).append(',').append(e.actor()).append(',')
                      .append(e.action()).append(',').append(e.objectId()).append(',')
                      .append(e.result()).append(',').append(e.reason()).append('\n');
                }
                Files.writeString(Path.of(export), "\uFEFF" + sb, StandardCharsets.UTF_8);
                out.println("已导出审计 CSV: " + export);
            }
            return 0;
        }
    }

    private static int sync(String[] args, PrintStream out, PrintStream err) throws IOException {
        Path input = Path.of(opt(args, "--input", "samples/failures-4w.jsonl"));
        List<String> lines = Files.readAllLines(input, StandardCharsets.UTF_8);
        try (Database db = Database.inMemory()) {
            BackpressureQueue<FailureEvent> queue = new BackpressureQueue<>(1000);
            EventCounter counter = new EventCounter();
            QueueWatermark watermark = new QueueWatermark(1000);
            FailureIngestPipeline pipeline = new FailureIngestPipeline(queue,
                    new EventArchiveDao(db), counter, watermark);
            var ingested = pipeline.collect(lines);
            List<FailureEvent> events = queue.drain();
            List<Credential> ledger = loadLedger(err);
            List<RootCauseCard> cards = new RuleAttributor().attribute(events, ledger);
            out.println("入队 " + ingested.enqueued() + " 条，拒收 " + ingested.rejected()
                    + " 条，队列深度 " + ingested.watermark().depth());
            for (RootCauseCard card : cards) out.print(card.render());
            out.println("结论卡 " + cards.size() + " 张（模型路径未配置时走规则基线）");
            return 0;
        }
    }

    private static int attribution(String[] args, PrintStream out, PrintStream err) throws IOException {
        Path input = Path.of(opt(args, "--input", "samples/failures-sample.jsonl"));
        var parsed = new com.med.icr.ingest.FailureEventParser().parse(input);
        Path ledgerFile = Path.of("samples/credentials.json");
        List<Credential> ledger = Files.exists(ledgerFile)
                ? SampleData.readLedger(ledgerFile) : List.of();
        List<RootCauseCard> cards = new RuleAttributor().attribute(parsed.events(), ledger);
        out.println("输入 " + parsed.events().size() + " 条，坏行 " + parsed.rejected().size() + " 条");
        for (RootCauseCard card : cards) out.print(card.render());
        out.println("单条级失败（字段口径错）不升级为凭据级结论");
        return 0;
    }

    private static List<Credential> loadLedger(PrintStream err) throws IOException {
        String base = System.getenv(AppConfig.CREDENTIAL_BASE_URL);
        String token = System.getenv(AppConfig.CREDENTIAL_TOKEN);
        if (base != null && !base.isBlank() && token != null && !token.isBlank()) {
            return new CredentialLedgerClient(base, token).fetch();
        }
        Path samples = Path.of("samples/credentials.json");
        if (Files.exists(samples)) return SampleData.readLedger(samples);
        err.println("未配置台账端点且无本地样例，清单为空");
        return List.of();
    }

    private static String firstConfigured(String... keys) {
        for (String key : keys) {
            String v = System.getenv(key);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static boolean has(String[] args, String flag) {
        for (String a : args) if (a.equals(flag)) return true;
        return false;
    }

    private static String opt(String[] args, String flag, String fallback) {
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals(flag)) return args[i + 1];
        return fallback;
    }

    private static Instant optInstant(String[] args, String flag, Instant fallback) {
        String v = opt(args, flag, null);
        return v == null ? fallback : com.med.icr.util.Times.parse(v);
    }
}
