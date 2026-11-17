package com.med.icr;

import com.fasterxml.jackson.databind.JsonNode;
import com.med.icr.attribution.RuleAttributor;
import com.med.icr.cli.Cli;
import com.med.icr.config.AppConfig;
import com.med.icr.domain.Credential;
import com.med.icr.domain.FailureEvent;
import com.med.icr.domain.RootCauseCard;
import com.med.icr.ingest.FailureEventParser;
import com.med.icr.ingest.FailureIngestPipeline;
import com.med.icr.pipeline.BackpressureQueue;
import com.med.icr.pipeline.EventCounter;
import com.med.icr.pipeline.QueueWatermark;
import com.med.icr.store.Database;
import com.med.icr.store.EventArchiveDao;
import com.med.icr.support.Fixtures;
import com.med.icr.tools.SampleData;
import com.med.icr.tools.SampleGenerator;
import com.med.icr.topk.ExpiryListExporter;
import com.med.icr.topk.RotationPlanner;
import com.med.icr.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** T3 / T5 / T47–T51：合成数据、CLI 分发、文档打包、截图证据、README 丰度与可重复验证流程。 */
final class DeliverySuites {}

class SampleGeneratorTest {
    @Test void twoRunsAreByteIdenticalAndReportScenarios(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("run-a");
        Path b = tmp.resolve("run-b");
        SampleGenerator.GenerationResult ra = SampleGenerator.generate(a, false);
        SampleGenerator.GenerationResult rb = SampleGenerator.generate(b, false);
        for (String name : List.of("credentials.json", "failures-4w.jsonl",
                "failures-4w-truth.jsonl", "failures-sample.jsonl", "public-certs.json")) {
            assertEquals(Fixtures.sha256(a.resolve(name)), Fixtures.sha256(b.resolve(name)),
                    "固定种子两次运行产物必须逐字节一致: " + name);
        }
        assertEquals(20261109L, SampleGenerator.SEED);
        assertTrue(ra.eventCount() >= 600, "4 周失败事件应 ≥600 条");
        assertEquals(8, ra.credentialCount());
        assertEquals(5, ra.sceneCounts().size());
        assertTrue(ra.sceneCounts().keySet().containsAll(List.of("A", "B", "C", "D", "E")));
        assertFalse(ra.publicSource().isBlank());
        assertEquals(ra.eventCount(), rb.eventCount());
        long lines = Files.readAllLines(a.resolve("failures-4w.jsonl")).size();
        assertEquals(ra.eventCount(), lines);
        Path report = tmp.resolve("validation.json");
        SampleGenerator.writeValidationReport(report, ra);
        JsonNode n = Json.MAPPER.readTree(Fixtures.read(report));
        assertTrue(n.has("scene_counts"));
        assertTrue(n.has("public_source"));
        assertEquals(ra.eventCount(), n.get("event_count").asInt());
        assertEquals(8, n.get("credential_count").asInt());
    }
}

class TrunkAttributionTest {
    @Test void minimalTrunkPrintsTwoCardsAndExcludesSingleSchemaError() {
        String text = run("--cli", "attribution", "--input", "samples/failures-sample.jsonl");
        assertEquals(2, occurrences(text, "根因结论卡（草稿）"), "应输出 2 张根因结论卡");
        assertFalse(text.contains("TRACE-C-001"), "单条字段口径错不得进入结论卡证据引用");
        assertTrue(text.contains("指认凭据：CRED-0007"));
        assertTrue(text.contains("指认凭据：CRED-0003"));
        assertTrue(text.contains("本次未使用模型归因"));
    }

    static int occurrences(String text, String needle) {
        int count = 0, from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) { count++; from += needle.length(); }
        return count;
    }

    static String run(String... args) {
        String[] cliArgs = args.length > 0 && "--cli".equals(args[0])
                ? java.util.Arrays.copyOfRange(args, 1, args.length) : args;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Cli.run(cliArgs,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, code, "命令退出码应为 0；stderr=" + err.toString(StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }
}

class CliTest {
    @Test void doctorPrintsElevenConfigRowsWithoutValues() {
        String text = TrunkAttributionTest.run("doctor");
        for (String key : AppConfig.KEYS) assertTrue(text.contains(key), "缺配置行 " + key);
        assertTrue(text.contains("未设置"));
        assertTrue(text.contains("必需项缺失数"));
        assertFalse(text.contains("SUPER-SECRET"));
    }

    @Test void scanPrintsExpiryList() {
        String text = TrunkAttributionTest.run("scan", "--as-of", "2026-10-12T00:00:00Z", "--warn-days", "7");
        assertTrue(text.contains("即将到期清单"));
        assertTrue(text.contains("CRED-0007"));
        assertTrue(text.contains("共 1 条"));
    }

    @Test void ticketWithoutConfirmIsRefused() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Cli.run(new String[]{"ticket"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(2, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("拒绝外发"));
    }

    @Test void unknownCommandAndHelpExitCodes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(2, Cli.run(new String[]{"no-such-command"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
        assertEquals(0, Cli.run(new String[]{"--help"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
    }

    @Test void syncReusesSharedBusinessLayer() {
        String text = TrunkAttributionTest.run("sync", "--input", "samples/failures-sample.jsonl");
        assertTrue(text.contains("入队 12 条"));
        assertTrue(text.contains("结论卡 2 张"));
        assertTrue(text.contains("CRED-0007"));
    }

    @Test void cliPackageDoesNotReimplementAggregation() throws Exception {
        String source = Fixtures.read(Path.of("src/main/java/com/med/icr/cli/Cli.java"));
        assertFalse(source.contains("aggregateByKey"));
        assertFalse(source.contains("WindowAggregator"));
        assertTrue(source.contains("RuleAttributor"), "应复用归因业务层");
    }
}

class PackagingDocsTest {
    @Test void docsPackagingAndLicenseArePresentWithRequiredSections() throws Exception {
        for (String p : List.of("docs/cli.md", "docs/architecture.md", "scripts/package.sh",
                "LICENSE", "pom.xml", ".env.example", ".gitignore", "docs/concepts/visual-goal.md")) {
            assertTrue(Files.exists(Path.of(p)), "缺交付文件 " + p);
        }
        String cli = Fixtures.read(Path.of("docs/cli.md"));
        assertTrue(cli.contains("退出码语义"));
        assertTrue(cli.contains("打包（院内拷贝即用）"));
        assertTrue(cli.contains("attribution"));
        String arch = Fixtures.read(Path.of("docs/architecture.md"));
        assertTrue(arch.contains("双状态机"));
        assertTrue(arch.contains("幂等键"));
        assertTrue(arch.contains("领域不变量"));
        assertTrue(arch.contains("模型降级路径"));
        assertTrue(Fixtures.read(Path.of("scripts/package.sh")).contains("jpackage"));
        assertTrue(Fixtures.read(Path.of("LICENSE")).contains("MIT"));
        assertTrue(Fixtures.read(Path.of("pom.xml")).contains("<main.class>com.med.icr.Main</main.class>"));
        assertTrue(Fixtures.read(Path.of("pom.xml")).contains("<mainClass>${main.class}</mainClass>"));
        assertTrue(Fixtures.read(Path.of("pom.xml")).contains("<addClasspath>true</addClasspath>"));
        long envKeys = Files.readAllLines(Path.of(".env.example")).stream()
                .filter(l -> l.contains("=") && !l.trim().startsWith("#")).count();
        assertEquals(11, envKeys, ".env.example 应有 11 项环境变量");
    }
}

class ReadmeArtifactsTest {
    @Test void abundanceAndNoPlaceholdersOrSourceRepoNames() throws Exception {
        String readme = Fixtures.read(Path.of("README.md"));
        long h2 = readme.lines().filter(l -> l.startsWith("## ")).count();
        long h3 = readme.lines().filter(l -> l.startsWith("### ")).count();
        long tableRows = readme.lines().filter(l -> l.startsWith("|")).count();
        long fences = readme.lines().filter(l -> l.startsWith("```")).count();
        assertTrue(h2 >= 10, "## 数 " + h2);
        assertTrue(h3 >= 8, "### 数 " + h3);
        assertTrue(tableRows >= 5);
        assertTrue(fences >= 8);
        assertTrue(readme.length() >= 5000, "正文字符 " + readme.length());
        assertTrue(readme.contains("┌"), "应含 ASCII 图");
        for (String banned : List.of("Phase 4", "后续 task 补齐", "Phase 5", "TODO", "TBD")) {
            assertFalse(readme.contains(banned), "残留占位符 " + banned);
        }
        for (String banned : List.of("github", "gitee", "iotdb", "github_ref", "NEWS_SEED")) {
            assertFalse(readme.toLowerCase().contains(banned), "README 不得出现源仓库名 " + banned);
        }
    }

    @Test void sixRealScreenshotsExistAndAreEachReferenced() throws Exception {
        String readme = Fixtures.read(Path.of("README.md"));
        List<String> names = List.of("01-expiry-list.png", "02-failure-timeline.png",
                "03-root-cause-card.png", "04-cli-scan.png", "05-cli-probe.png", "06-cli-attribution.png");
        for (String name : names) {
            Path p = Path.of("docs/screenshots", name);
            assertTrue(Files.exists(p), "缺实测截图 " + name);
            assertTrue(Files.size(p) > 1000, "截图不应为空 " + name);
            assertTrue(readme.contains(name), "README 未逐张引用 " + name);
        }
        assertTrue(Files.exists(Path.of("qrcode.jpg")));
    }
}

class ReleaseCleanlinessTest {
    @Test void deliverableTreeIsCompleteAndNoSecretsCommitted() {
        for (String p : List.of("src/main/java", "src/test/java", "README.md", "LICENSE",
                "docs/concepts", "docs/screenshots", "qrcode.jpg", "samples", "reports", "scripts")) {
            assertTrue(Files.exists(Path.of(p)), "发布目录缺项 " + p);
        }
        assertFalse(Files.exists(Path.of(".env")), "不得提交 .env");
        assertFalse(Files.exists(Path.of(".localstate")), "不得带本地状态库发布");
    }
}

class RepeatableFlowTest {
    @Test void installConfigReplayProducesOneCardAndOneExpiryList() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put(AppConfig.ESB_BASE_URL, "https://esb.intra.example");
        env.put(AppConfig.ESB_TOKEN, "token-a");
        env.put(AppConfig.CREDENTIAL_BASE_URL, "https://cred.intra.example");
        env.put(AppConfig.CREDENTIAL_TOKEN, "token-b");
        env.put(AppConfig.FINGERPRINT_KEY, "hmac-key");
        AppConfig cfg = AppConfig.from(env);
        assertEquals("https://esb.intra.example", cfg.require(AppConfig.ESB_BASE_URL));
        assertFalse(cfg.modelConfigured(), "模型未配置时走规则基线");

        List<String> lines = Files.readAllLines(Path.of("samples/failures-sample.jsonl"), StandardCharsets.UTF_8);
        try (Database db = Database.inMemory()) {
            BackpressureQueue<FailureEvent> queue = new BackpressureQueue<>(1000);
            FailureIngestPipeline pipeline = new FailureIngestPipeline(queue, new EventArchiveDao(db),
                    new EventCounter(), new QueueWatermark(1000));
            FailureIngestPipeline.IngestResult ingested = pipeline.collect(lines);
            assertEquals(12, ingested.enqueued());
            List<Credential> ledger = SampleData.readLedger(Path.of("samples/credentials.json"));
            List<RootCauseCard> cards = new RuleAttributor().attribute(queue.drain(), ledger);
            assertEquals(2, cards.size(), "可重复验证流程应产出根因结论卡");
            assertNotNull(cards.get(0).credentialId());
            assertTrue(cards.get(0).render().contains("根因结论卡（草稿）"));
            assertFalse(cards.stream().anyMatch(c -> c.evidenceRefs().contains("TRACE-C-001")));

            List<RotationPlanner.RotationRow> rows = new RotationPlanner().plan(ledger,
                    Instant.parse("2026-10-12T00:00:00Z"), 7);
            String csv = ExpiryListExporter.toCsv(rows);
            assertTrue(csv.startsWith(ExpiryListExporter.HEADER));
            assertEquals(rows.size() + 1, csv.lines().count());
            assertTrue(csv.contains("CRED-0007"));
        }
    }
}
