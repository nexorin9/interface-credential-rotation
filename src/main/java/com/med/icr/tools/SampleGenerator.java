package com.med.icr.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.med.icr.domain.Fingerprint;
import com.med.icr.util.Json;
import com.med.icr.util.Times;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 合成数据生成器：固定种子、可重跑、byte 级一致。
 * 输出 4 周 × 3 通道 ≈660 条失败事件（场景 A–E）、8 条凭据台账、最小样例与公开证书样本。
 * 样例里不出现拟真批号 / 住院号 / 处方号 / 患者化名，标识一律占位或类目。
 */
public final class SampleGenerator {
    public static final long SEED = 20261109L;
    public static final String[] CHANNELS = {"CH-01", "CH-02", "CH-03"};
    public static final Instant BASE = Instant.parse("2026-10-12T00:00:00Z");
    private static final String FP_KEY = "SYNTHETIC-FINGERPRINT-KEY-DO-NOT-USE-IN-PRODUCTION";

    public record GenerationResult(int eventCount, int credentialCount, int sampleEventCount,
                                   int publicCertCount, String publicSource,
                                   Map<String, Integer> sceneCounts, int duplicateGroups,
                                   int noFingerprintEvents, int fingerprintGroups) {}

    private SampleGenerator() {}

    public static void main(String[] args) throws Exception {
        Path out = Path.of("samples");
        Path report = Path.of("reports/sample-validation.json");
        boolean pull = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = Path.of(args[++i]);
                case "--report" -> report = Path.of(args[++i]);
                case "--pull-public-certs" -> pull = true;
                case "--help" -> {
                    System.out.println("用法: SampleGenerator [--out samples] [--report reports/sample-validation.json] [--pull-public-certs]");
                    return;
                }
                default -> System.err.println("忽略未知参数: " + args[i]);
            }
        }
        GenerationResult result = generate(out, pull);
        writeValidationReport(report, result);
        System.out.println("合成样例生成完成: events=" + result.eventCount()
                + " credentials=" + result.credentialCount()
                + " public_source=" + result.publicSource()
                + " certs=" + result.publicCertCount()
                + " report=" + report);
    }

    public static GenerationResult generate(Path out, boolean pullPublic) throws IOException {
        Files.createDirectories(out);
        Random random = new Random(SEED);
        Map<String, Integer> scenes = new LinkedHashMap<>();
        for (String s : List.of("A", "B", "C", "D", "E")) scenes.put(s, 0);

        // 8 条凭据台账（元数据 + 指纹，无任何明文）
        ArrayNode credentials = Json.MAPPER.createArrayNode();
        String[] downstream = {"卫健数据上报", "医保平台", "区域健康档案", "第三方检验影像"};
        for (int i = 1; i <= 8; i++) {
            String id = String.format("CRED-%04d", i);
            String type = i == 7 ? "cert" : (i % 3 == 0 ? "api_key" : "password");
            String secret = "SYNTHETIC-SECRET-" + id; // 只参与指纹计算，绝不写入任何产物
            String fp = Fingerprint.of(id, secret, FP_KEY);
            List<String> channels = i == 7
                    ? List.of("CH-01", "CH-02", "CH-03")
                    : List.of(CHANNELS[(i - 1) % CHANNELS.length]);
            ObjectNode c = Json.MAPPER.createObjectNode();
            c.put("credential_id", id);
            c.put("channel_code", channels.get(0));
            c.put("credential_type", type);
            c.put("expires_at", Times.format(BASE.plusSeconds(86400L * (i == 7 ? 3 : 20 + i * 11))));
            c.put("owner_dept", "信息科");
            c.put("owner", "集成运维岗");
            c.put("rotation_entry", "厂商服务台 / 换证入口");
            c.putNull("last_verified_at");
            c.put("fingerprint", fp);
            ArrayNode ch = c.putArray("channel_codes");
            channels.forEach(ch::add);
            ArrayNode ds = c.putArray("downstream_systems");
            ds.add(downstream[(i - 1) % downstream.length]);
            if (i == 7) for (String d : downstream) ds.add(d);
            credentials.add(c);
        }
        Files.writeString(out.resolve("credentials.json"),
                Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(credentials) + "\n",
                StandardCharsets.UTF_8);

        String fp7 = credentials.get(6).get("fingerprint").asText();
        String fp3 = credentials.get(2).get("fingerprint").asText();

        // 4 周失败事件：场景 A–E，总 660 条
        List<String> lines = new ArrayList<>();
        List<String> truth = new ArrayList<>();
        int duplicateGroups = 0;
        int noFingerprint = 0;
        int eventIndex = 0;
        String[] certErrors = {
                "TLS handshake failed: certificate has expired", "javax.net.ssl.SSLHandshakeException: cert expired",
                "nginx: SSL peer certificate expired", "网关验签失败，对端证书不在有效期内",
                "HTTP 403 credential rejected for CRED-0007", "openssl verify error 10: certificate has expired"};
        String[] apiErrors = {
                "HTTP 403 api key revoked", "鉴权服务返回 key has been revoked",
                "java 401 unauthorized: token invalid", "网关 403: API Key 已吊销"};
        for (int week = 0; week < 4; week++) {
            for (int day = 0; day < 7; day++) {
                for (int slot = 0; slot < 24; slot++) {
                    Instant occurred = BASE.plusSeconds(week * 7L * 86400 + day * 86400L + slot * 3600L);
                    if (eventIndex % 11 == 0) {
                        // 场景 C：单条字段口径错，无指纹，不得升级为凭据级
                        // 少量 C 场景报文夹带凭据号文本，作为基准里的典型误聚诱饵
                        String cMsg = eventIndex % 22 == 0
                                ? "字段口径错: <EXAMPLE_FIELD> 与接口规范不符，附带 CRED-0007 文本但不构成凭据证据"
                                : "字段口径错: <EXAMPLE_FIELD> 与接口规范不符";
                        lines.add(eventLine(eventIndex, CHANNELS[eventIndex % 3], "field_schema",
                                cMsg, occurred, null, null, "C", null));
                        truth.add(truthLine(eventIndex, "C", "UNRESOLVED"));
                        scenes.merge("C", 1, Integer::sum);
                        noFingerprint++;
                    } else if (eventIndex % 13 == 0) {
                        // 场景 D：模型不可达（构造不响应端点），无指纹
                        lines.add(eventLine(eventIndex, CHANNELS[eventIndex % 3], "model_unavailable",
                                "推理端点不可达，归因回落到规则基线", occurred, null, null, "D", null));
                        truth.add(truthLine(eventIndex, "D", "UNRESOLVED"));
                        scenes.merge("D", 1, Integer::sum);
                        noFingerprint++;
                    } else if (eventIndex % 9 == 0) {
                        // 场景 E：同窗口重复上报（同 trace 两条）
                        String trace = String.format("TRACE-DUP-%04d", eventIndex);
                        for (int dup = 0; dup < 2; dup++) {
                            lines.add(eventLine(eventIndex * 10 + dup, CHANNELS[eventIndex % 3], "duplicate_report",
                                    "重复上报: fp:" + fp3, occurred.plusSeconds(dup), fp3, trace, "E", "CRED-0003"));
                            truth.add(truthLine(eventIndex * 10 + dup, "E", "CRED-0003"));
                            scenes.merge("E", 1, Integer::sum);
                            eventIndex++;
                        }
                        duplicateGroups++;
                        eventIndex--;
                    } else if (eventIndex % 4 == 0) {
                        // 场景 B：API Key 被吊销（403），部分不带指纹但报错文本含线索
                        boolean missingFp = eventIndex % 8 == 0;
                        String msg = apiErrors[eventIndex % apiErrors.length] + " fp:" + fp3;
                        lines.add(eventLine(eventIndex, CHANNELS[eventIndex % 3], "auth_revoked", msg,
                                occurred, missingFp ? null : fp3, null, "B", "CRED-0003"));
                        truth.add(truthLine(eventIndex, "B", "CRED-0003"));
                        scenes.merge("B", 1, Integer::sum);
                        if (missingFp) noFingerprint++;
                    } else {
                        // 场景 A：证书过期，多通道同时报错、报错文本异构
                        boolean missingFp = eventIndex % 7 == 0;
                        String msg = certErrors[eventIndex % certErrors.length] + " fp:" + fp7;
                        lines.add(eventLine(eventIndex, CHANNELS[eventIndex % 3], "cert_expired", msg,
                                occurred, missingFp ? null : fp7, null, "A", "CRED-0007"));
                        truth.add(truthLine(eventIndex, "A", "CRED-0007"));
                        scenes.merge("A", 1, Integer::sum);
                        if (missingFp) noFingerprint++;
                    }
                    eventIndex++;
                }
            }
        }
        Files.write(out.resolve("failures-4w.jsonl"), lines, StandardCharsets.UTF_8);
        Files.write(out.resolve("failures-4w-truth.jsonl"), truth, StandardCharsets.UTF_8);

        // 最小样例：同指纹 8 条 / 另一指纹 3 条 / 单条字段口径错 1 条
        List<String> minimal = new ArrayList<>();
        Instant m0 = Instant.parse("2026-11-09T08:00:00Z");
        for (int i = 0; i < 8; i++) {
            minimal.add(eventLine(9000 + i, CHANNELS[i % 3], i % 2 == 0 ? "cert_expired" : "tls_handshake",
                    certErrors[i % certErrors.length], m0.plusSeconds(i * 20L), fp7,
                    String.format("TRACE-A-%03d", i), "A", "CRED-0007"));
        }
        for (int i = 0; i < 3; i++) {
            minimal.add(eventLine(9100 + i, CHANNELS[i % 3], "auth_revoked", apiErrors[i],
                    m0.plusSeconds(200 + i * 20L), fp3, String.format("TRACE-B-%03d", i), "B", "CRED-0003"));
        }
        minimal.add(eventLine(9200, "CH-03", "field_schema",
                "字段口径错: <EXAMPLE_FIELD> 与接口规范不符", m0.plusSeconds(280), null,
                "TRACE-C-001", "C", "UNRESOLVED"));
        Files.write(out.resolve("failures-sample.jsonl"), minimal, StandardCharsets.UTF_8);

        // 公开证书样本：真实拉取失败即回退合成，标注来源
        ArrayNode certs = Json.MAPPER.createArrayNode();
        String publicSource = "fallback-synthetic";
        if (pullPublic) {
            ArrayNode pulled = pullPublicCerts();
            if (pulled != null && pulled.size() >= 20) {
                certs = pulled;
                publicSource = "crt.sh";
            }
        }
        if (publicSource.equals("fallback-synthetic")) {
            for (int i = 0; i < 24; i++) {
                ObjectNode c = Json.MAPPER.createObjectNode();
                c.put("serial", "<EXAMPLE_CERT_" + i + ">");
                c.put("not_before", Times.format(BASE.minusSeconds(86400L * (400 + i))));
                c.put("not_after", Times.format(BASE.plusSeconds(86400L * (i % 6 - 2))));
                c.put("issuer", "SYNTHETIC-ISSUER-" + (i % 4 + 1));
                c.put("source", "fallback-synthetic");
                certs.add(c);
            }
        }
        Files.writeString(out.resolve("public-certs.json"),
                Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(certs) + "\n",
                StandardCharsets.UTF_8);

        return new GenerationResult(lines.size(), 8, minimal.size(), certs.size(), publicSource,
                scenes, duplicateGroups, noFingerprint, 2);
    }

    private static String eventLine(int index, String channel, String errorClass, String message,
                                    Instant occurred, String fingerprint, String trace,
                                    String scene, String expected) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("event_id", String.format("EVT-%06d", index));
        n.put("channel_code", channel);
        n.put("error_class", errorClass);
        n.put("error_message", message);
        n.put("occurred_at", Times.format(occurred));
        if (fingerprint == null) n.putNull("credential_fingerprint");
        else n.put("credential_fingerprint", fingerprint);
        n.put("trace_id", trace == null ? String.format("TRACE-%06d", index) : trace);
        n.put("endpoint", "<EXAMPLE_ENDPOINT>");
        n.put("upstream_service", "卫健数据上报 / 医保平台 / 区域健康档案");
        n.put("scene", scene);
        n.put("expected_credential_id", expected == null ? "UNRESOLVED" : expected);
        return n.toString();
    }

    private static String truthLine(int index, String scene, String expected) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("event_id", String.format("EVT-%06d", index));
        n.put("scene", scene);
        n.put("expected_credential_id", expected);
        return n.toString();
    }

    /** 从证书透明度日志匿名拉取公开元数据；超时 / 403 / 无外网时返回 null 触发合成降级。 */
    private static ArrayNode pullPublicCerts() {
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("https://crt.sh/?q=expired&output=json"))
                    .timeout(Duration.ofSeconds(6)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            JsonNode root = Json.MAPPER.readTree(response.body());
            if (!root.isArray() || root.size() < 20) return null;
            ArrayNode out = Json.MAPPER.createArrayNode();
            for (int i = 0; i < Math.min(24, root.size()); i++) {
                JsonNode row = root.get(i);
                ObjectNode c = Json.MAPPER.createObjectNode();
                c.put("serial", "<PUBLIC_CERT_" + i + ">");
                c.put("not_before", row.path("not_before").asText(""));
                c.put("not_after", row.path("not_after").asText(""));
                c.put("issuer", row.path("issuer_name").asText(""));
                c.put("source", "crt.sh");
                out.add(c);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public static void writeValidationReport(Path report, GenerationResult r) throws IOException {
        Files.createDirectories(report.toAbsolutePath().getParent());
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("generator", "SampleGenerator");
        root.put("seed", SEED);
        root.put("event_count", r.eventCount());
        root.put("credential_count", r.credentialCount());
        root.put("minimal_sample_event_count", r.sampleEventCount());
        root.put("channel_code_count", CHANNELS.length);
        ArrayNode channels = root.putArray("channel_codes");
        for (String c : CHANNELS) channels.add(c);
        ObjectNode scene = root.putObject("scene_counts");
        r.sceneCounts().forEach(scene::put);
        root.put("fingerprint_duplicate_groups", r.fingerprintGroups());
        root.put("no_fingerprint_events", r.noFingerprintEvents());
        root.put("duplicate_report_groups", r.duplicateGroups());
        root.put("public_source", r.publicSource());
        root.put("public_cert_count", r.publicCertCount());
        root.put("identifiers", "占位或类目，无拟真批号 / 住院号 / 处方号 / 患者化名");
        Files.writeString(report,
                Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                StandardCharsets.UTF_8);
    }
}
