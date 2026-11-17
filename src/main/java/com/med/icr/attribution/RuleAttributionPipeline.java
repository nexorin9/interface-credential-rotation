package com.med.icr.attribution;

import com.med.icr.domain.RootCauseCard;
import com.med.icr.model.ModelResponse;

import java.util.List;

/** 规则版管道：model 段由确定性基线填充，输出仍按三段走 postprocess 校验。 */
public final class RuleAttributionPipeline extends AbstractAttributionPipeline {
    public RuleAttributionPipeline() { this(new RuleAttributor()); }
    public RuleAttributionPipeline(RuleAttributor attributor) { super(attributor); }

    @Override
    protected ModelResponse model(Context ctx) {
        List<RootCauseCard> cards = ruleAttributor.attribute(ctx.events(), ctx.ledger());
        if (cards.isEmpty()) {
            return new ModelResponse("无可用结论，需人工关联", "UNRESOLVED", 0.0,
                    List.of(), "人工核对台账后重扫", "EVENT_NON_CRITICAL");
        }
        RootCauseCard card = cards.get(0);
        return new ModelResponse(card.rootCause(),
                card.credentialId() == null ? "UNRESOLVED" : card.credentialId(),
                (card.confidenceLower() + card.confidenceUpper()) / 2, card.evidenceRefs(),
                card.suggestedAction(), card.severity().equals("通道级") ? "CHANNEL_CRITICAL" : "EVENT_NON_CRITICAL");
    }
}
