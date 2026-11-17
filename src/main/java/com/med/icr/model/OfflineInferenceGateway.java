package com.med.icr.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线确定性语义推断替身（仅用于对比基准与断网回放，不是生产模型路径）。
 * 从脱敏报错摘要里识别凭据线索并映射台账：模拟「事件不带指纹时由模型反推凭据」这一步。
 * 报告里明确标注 model_mode=offline-deterministic-stand-in。
 */
public final class OfflineInferenceGateway implements ModelGateway {
    public static final String MODE = "offline-deterministic-stand-in";
    private static final Pattern CRED = Pattern.compile("CRED-\\d{4}");
    private static final Pattern FP = Pattern.compile("fp:([0-9a-f]{16})");

    @Override
    public ModelResponse infer(ModelRequest request) {
        Map<String, String> fpToCred = new LinkedHashMap<>();
        for (ModelRequest.CredentialSummary c : request.credentials()) {
            if (c.fingerprint() != null) fpToCred.putIfAbsent(c.fingerprint(), c.credentialId());
        }
        String inferred = null;
        List<String> refs = new ArrayList<>();
        for (ModelRequest.EventSummary e : request.events()) {
            refs.add(e.evidenceRef());
            String msg = e.maskedMessage() == null ? "" : e.maskedMessage();
            Matcher fp = FP.matcher(msg);
            Matcher cred = CRED.matcher(msg);
            // 只依据报错文本里的语义线索推断，不用通道号硬凑，避免制造假结论
            if (fp.find()) inferred = fpToCred.get(fp.group(1));
            else if (cred.find()) inferred = cred.group();
        }
        String rootCause = inferred == null
                ? "报错摘要不足以指认凭据，需人工关联"
                : "异构报错经语义推断集中指向同一张凭据，建议核对到期时间与换证入口";
        return new ModelResponse(rootCause, inferred == null ? "UNRESOLVED" : inferred,
                inferred == null ? 0.2 : 0.78, refs, "联系厂商换证后重跑连通性自检",
                request.events().size() > 1 ? "CHANNEL_CRITICAL" : "EVENT_NON_CRITICAL");
    }

    @Override public String name() { return "offline-inference-stand-in"; }
}
