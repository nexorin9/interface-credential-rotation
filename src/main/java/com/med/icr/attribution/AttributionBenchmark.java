package com.med.icr.attribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.med.icr.domain.Credential;
import com.med.icr.domain.CredentialType;
import com.med.icr.domain.FailureEvent;
import com.med.icr.ingest.FailureEventParser;
import com.med.icr.model.ModelRequest;
import com.med.icr.model.OfflineInferenceGateway;
import com.med.icr.util.Json;
import com.med.icr.util.Masking;
import com.med.icr.util.Times;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 对比基准：在场景 A–E 离线集上把「纯规则基线」与「模型参与」摆在一起量。
 * 固定随机种子、可重跑：两次运行产出文件逐字节相同；误聚案例逐条给出事件 id。
 * 模型参与路径在断网条件下使用确定性语义推断替身，报告里显式标注 model_mode。
 */
public final class AttributionBenchmark {
    public static final String UNRESOLVED = "UNRESOLVED";

    public record Detail(String eventId, String scene, String expected,
                         String rulePredicted, String modelPredicted) {}
    public record Metrics(double precision, double recall, double misclusterRate,
                          int tp, int fp, int fn, int tn, int miscluster) {}
    public record MisclusterCase(String path, String eventId, String scene,
                                 String expected, String predicted) {}
    public record BenchmarkResult(List<Detail> details, Metrics rule, Metrics model,
                                  List<MisclusterCase> misclusterCases, int sampleSize) {}

    private AttributionBenchmark() {}

    public static BenchmarkResult run(Path eventsFile, Path truthFile, Path ledgerFile) throws IOException {
        FailureEventParser.ParseResult parsed = new FailureEventParser().parse(eventsFile);
        Map<String, String> expectedById = new HashMap<>();
        Map<String, String> sceneById = new HashMap<>();
        for (String line : Files.readAllLines(truthFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode n = Json.MAPPER.readTree(line);
            expectedById.put(n.get("event_id").asText(), n.get("expected_credential_id").asText());
            sceneById.put(n.get("event_id").asText(), n.get("scene").asText());
        }
        List<Credential> ledger = readLedger(ledgerFile);
        Map<String, String> fpToCred = new HashMap<>();
        List<ModelRequest.CredentialSummary> summaries = new ArrayList<>();
        for (Credential c : ledger) {
            if (c.fingerprint() != null) fpToCred.put(c.fingerprint(), c.credentialId());
            summaries.add(new ModelRequest.CredentialSummary(c.credentialId(), c.channelCode(),
                    c.credentialType().code(), c.fingerprint(), null));
        }
        OfflineInferenceGateway inference = new OfflineInferenceGateway();
        List<Detail> details = new ArrayList<>();
        for (FailureEvent e : parsed.events()) {
            String expected = expectedById.getOrDefault(e.eventId(), UNRESOLVED);
            String scene = sceneById.getOrDefault(e.eventId(), "-");
            String rule = e.hasFingerprint() ? fpToCred.getOrDefault(e.credentialFingerprint(), UNRESOLVED) : UNRESOLVED;
            ModelRequest.EventSummary one = new ModelRequest.EventSummary(e.eventId(), e.channelCode(),
                    e.errorClass(), Masking.apply(e.errorMessage()), e.evidenceRef(), Times.format(e.occurredAt()));
            String model = inference.infer(new ModelRequest(List.of(one), summaries, "benchmark")).credentialId();
            if (model == null || model.isBlank()) model = UNRESOLVED;
            details.add(new Detail(e.eventId(), scene, expected, rule, model));
        }
        details.sort((a, b) -> a.eventId().compareTo(b.eventId()));
        Metrics ruleMetrics = metrics(details, "rule");
        Metrics modelMetrics = metrics(details, "model");
        List<MisclusterCase> cases = new ArrayList<>();
        for (Detail d : details) {
            if (!d.rulePredicted().equals(UNRESOLVED) && !d.rulePredicted().equals(d.expected())) {
                cases.add(new MisclusterCase("rule_baseline", d.eventId(), d.scene(), d.expected(), d.rulePredicted()));
            }
            if (!d.modelPredicted().equals(UNRESOLVED) && !d.modelPredicted().equals(d.expected())) {
                cases.add(new MisclusterCase("model_participating", d.eventId(), d.scene(), d.expected(), d.modelPredicted()));
            }
        }
        return new BenchmarkResult(details, ruleMetrics, modelMetrics, cases, details.size());
    }

    /** 从逐条明细重算三项指标，供测试断言报告自洽。 */
    public static Metrics metricsFromDetails(List<Detail> details, String path) {
        List<Detail> copy = new ArrayList<>(details);
        copy.sort((a, b) -> a.eventId().compareTo(b.eventId()));
        return metrics(copy, path);
    }

    private static Metrics metrics(List<Detail> details, String path) {
        int tp = 0, fp = 0, fn = 0, tn = 0, miscluster = 0;
        for (Detail d : details) {
            String predicted = path.equals("rule") ? d.rulePredicted() : d.modelPredicted();
            boolean expectedConcrete = !d.expected().equals(UNRESOLVED);
            boolean predictedConcrete = !predicted.equals(UNRESOLVED);
            if (expectedConcrete && predictedConcrete && predicted.equals(d.expected())) tp++;
            else if (expectedConcrete) fn++;
            else if (predictedConcrete) fp++;
            else tn++;
            if (predictedConcrete && !predicted.equals(d.expected())) miscluster++;
        }
        double precision = tp + fp == 0 ? 0.0 : round((double) tp / (tp + fp));
        double recall = tp + fn == 0 ? 0.0 : round((double) tp / (tp + fn));
        double misRate = details.isEmpty() ? 0.0 : round((double) miscluster / details.size());
        return new Metrics(precision, recall, misRate, tp, fp, fn, tn, miscluster);
    }

    private static double round(double v) { return Math.round(v * 10000.0) / 10000.0; }

    public static void writeReport(Path out, BenchmarkResult r) throws IOException {
        Files.createDirectories(out.toAbsolutePath().getParent());
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("generator", "AttributionBenchmark");
        root.put("seed", 20261109L);
        root.put("sample_size", r.sampleSize());
        root.put("model_mode", OfflineInferenceGateway.MODE);
        root.put("model_mode_note", "断网离线基准的模型参与路径使用确定性语义推断替身，非生产模型端点");
        root.set("rule_baseline", metricsNode(r.rule(), "rule"));
        root.set("model_participating", metricsNode(r.model(), "model"));
        Map<String, int[]> scene = new TreeMap<>();
        for (Detail d : r.details()) {
            int[] counts = scene.computeIfAbsent(d.scene(), k -> new int[4]);
            boolean expectedConcrete = !d.expected().equals(UNRESOLVED);
            boolean ruleHit = !d.rulePredicted().equals(UNRESOLVED) && d.rulePredicted().equals(d.expected());
            boolean modelHit = !d.modelPredicted().equals(UNRESOLVED) && d.modelPredicted().equals(d.expected());
            counts[0]++;
            if (expectedConcrete) counts[1]++;
            if (ruleHit) counts[2]++;
            if (modelHit) counts[3]++;
        }
        ObjectNode sceneNode = root.putObject("per_scene");
        for (Map.Entry<String, int[]> e : scene.entrySet()) {
            ObjectNode n = sceneNode.putObject(e.getKey());
            n.put("events", e.getValue()[0]);
            n.put("expected_concrete", e.getValue()[1]);
            n.put("rule_hits", e.getValue()[2]);
            n.put("model_hits", e.getValue()[3]);
        }
        ArrayNode cases = root.putArray("miscluster_cases");
        for (MisclusterCase c : r.misclusterCases()) {
            ObjectNode n = cases.addObject();
            n.put("path", c.path());
            n.put("event_id", c.eventId());
            n.put("scene", c.scene());
            n.put("expected", c.expected());
            n.put("predicted", c.predicted());
        }
        ArrayNode perEvent = root.putArray("per_event");
        for (Detail d : r.details()) {
            ObjectNode n = perEvent.addObject();
            n.put("event_id", d.eventId());
            n.put("scene", d.scene());
            n.put("expected", d.expected());
            n.put("rule_predicted", d.rulePredicted());
            n.put("model_predicted", d.modelPredicted());
        }
        Files.writeString(out,
                Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                StandardCharsets.UTF_8);
    }

    private static ObjectNode metricsNode(Metrics m, String path) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("path", path);
        n.put("precision", m.precision());
        n.put("recall", m.recall());
        n.put("miscluster_rate", m.misclusterRate());
        ObjectNode matrix = n.putObject("confusion_matrix");
        matrix.put("tp", m.tp());
        matrix.put("fp", m.fp());
        matrix.put("fn", m.fn());
        matrix.put("tn", m.tn());
        n.put("miscluster_count", m.miscluster());
        return n;
    }

    /** 供测试从报告逐条明细重算指标。 */
    public static List<Detail> readDetails(Path report) throws IOException {
        JsonNode root = Json.MAPPER.readTree(Files.readString(report, StandardCharsets.UTF_8));
        List<Detail> out = new ArrayList<>();
        for (JsonNode n : root.path("per_event")) {
            out.add(new Detail(n.get("event_id").asText(), n.get("scene").asText(),
                    n.get("expected").asText(), n.get("rule_predicted").asText(),
                    n.get("model_predicted").asText()));
        }
        return out;
    }

    private static List<Credential> readLedger(Path ledgerFile) throws IOException {
        JsonNode root = Json.MAPPER.readTree(Files.readString(ledgerFile, StandardCharsets.UTF_8));
        List<Credential> out = new ArrayList<>();
        for (JsonNode n : root) {
            List<String> channels = new ArrayList<>();
            if (n.path("channel_codes").isArray()) for (JsonNode c : n.path("channel_codes")) channels.add(c.asText());
            List<String> downstream = new ArrayList<>();
            if (n.path("downstream_systems").isArray()) for (JsonNode d : n.path("downstream_systems")) downstream.add(d.asText());
            String exp = n.path("expires_at").asText(null);
            out.add(new Credential(n.get("credential_id").asText(), n.get("channel_code").asText(),
                    CredentialType.parse(n.get("credential_type").asText()),
                    exp == null ? null : Times.parse(exp), n.path("owner_dept").asText("信息科"),
                    n.path("owner").asText("集成运维岗"), n.path("rotation_entry").asText("换证入口"),
                    null, n.path("fingerprint").asText(null), null, channels, downstream));
        }
        return out;
    }
}
