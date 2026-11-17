package com.med.icr.attribution;

import com.med.icr.domain.Credential;
import com.med.icr.domain.FailureEvent;
import com.med.icr.domain.RootCauseCard;
import com.med.icr.model.ModelRequest;
import com.med.icr.model.ModelResponse;
import com.med.icr.model.ModelUnavailableException;
import com.med.icr.util.Masking;
import com.med.icr.util.Times;

import java.util.ArrayList;
import java.util.List;

/** 模板方法：preprocess → model → postprocess 三段固定，核心由子类实现。 */
public abstract class AbstractAttributionPipeline implements AttributionPipeline {
    protected final RuleAttributor ruleAttributor;
    protected final EvidenceRefChecker evidenceChecker = new EvidenceRefChecker();

    protected AbstractAttributionPipeline(RuleAttributor ruleAttributor) {
        this.ruleAttributor = ruleAttributor;
    }

    @Override
    public final Result run(Context context) {
        Context prepared;
        try {
            prepared = preprocess(context);
        } catch (RuntimeException e) {
            return degrade(context, "PREPROCESS_ERROR");
        }
        try {
            ModelResponse response = model(prepared);
            Result result = postprocess(prepared, response);
            if (!result.compliant()) return degrade(prepared, "MODEL_OUTPUT_INVALID");
            return result;
        } catch (ModelUnavailableException e) {
            return degrade(prepared, e.code);
        } catch (RuntimeException e) {
            return degrade(prepared, "PIPELINE_ERROR");
        }
    }

    /** preprocess：台账 + 通道 + 证据组装，输入条数按 MODEL_MAX_INPUT_EVENTS 截断。 */
    protected Context preprocess(Context ctx) {
        List<ModelRequest.EventSummary> events = new ArrayList<>();
        int limit = Math.max(1, ctx.maxInputEvents());
        for (FailureEvent e : ctx.events()) {
            if (events.size() >= limit) break;
            events.add(new ModelRequest.EventSummary(e.eventId(), e.channelCode(), e.errorClass(),
                    Masking.apply(e.errorMessage()), e.evidenceRef(), Times.format(e.occurredAt())));
        }
        List<ModelRequest.CredentialSummary> credentials = new ArrayList<>();
        for (Credential c : ctx.ledger()) {
            credentials.add(new ModelRequest.CredentialSummary(c.credentialId(), c.channelCode(),
                    c.credentialType().code(), c.fingerprint(),
                    c.expiresAt() == null ? null : Times.format(c.expiresAt())));
        }
        ModelRequest request = new ModelRequest(events, credentials,
                "仅依据脱敏报错摘要与台账元数据推断凭据与根因");
        return new Context(ctx.events(), ctx.ledger(), ctx.modelUsed(), request, ctx.maxInputEvents());
    }

    /** model 段：规则版与模型版各自实现。 */
    protected abstract ModelResponse model(Context ctx) throws ModelUnavailableException;

    /** postprocess：结构校验 + 证据引用合法性核对，任一不合规整卡作废。 */
    protected Result postprocess(Context ctx, ModelResponse response) {
        EvidenceRefChecker.Result refs = evidenceChecker.check(response.evidenceRefs(), ctx.events());
        if (!refs.valid()) return new Result(null, false, refs.reason(), false);
        // 台账为空时走最窄主干，跳过权威主键核对（由后续人工关联补齐）
        if (!ctx.ledger().isEmpty()) {
            EvidenceRefChecker.Result cred = evidenceChecker.checkCredential(response.credentialId(), ctx.ledger());
            if (!cred.valid()) return new Result(null, false, cred.reason(), false);
        }
        double c = Math.max(0, Math.min(1, response.confidence()));
        RootCauseCard card = new RootCauseCard(response.rootCause(), response.credentialId(),
                response.evidenceRefs(), response.suggestedAction(),
                Math.max(0, c - 0.08), Math.min(1, c + 0.08), response.severity(),
                ctx.modelUsed(), firstWindow(ctx.events()), response.evidenceRefs().size(), "-");
        return new Result(card, !ctx.modelUsed(), ctx.modelUsed() ? "OK" : "RULE_BASELINE", true);
    }

    /** 降级：回落规则归因，卡片显式标注未使用模型，任何状态都不推进。 */
    protected Result degrade(Context ctx, String errorCode) {
        List<RootCauseCard> rules = ruleAttributor.attribute(ctx.events(), ctx.ledger());
        RootCauseCard card = rules.isEmpty() ? null : rules.get(0).asDegraded();
        return new Result(card, true, errorCode, card != null);
    }

    private static java.time.Instant firstWindow(List<FailureEvent> events) {
        return events.stream().map(FailureEvent::occurredAt).min(java.time.Instant::compareTo).orElse(null);
    }
}
