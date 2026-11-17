package com.med.icr.attribution;

import com.med.icr.domain.Credential;
import com.med.icr.domain.FailureEvent;
import com.med.icr.domain.RootCauseCard;

import java.util.List;
import java.util.Objects;

/** 降级策略：模型不可达 / 输出不合规 → 规则归因 + 置信度下调，状态一律不推进。 */
public final class DegradationPolicy {
    private final RuleAttributor ruleAttributor = new RuleAttributor();

    public RootCauseCard degrade(List<FailureEvent> events, List<Credential> ledger) {
        List<RootCauseCard> cards = ruleAttributor.attribute(events, ledger);
        return cards.isEmpty() ? null : cards.get(0).asDegraded();
    }

    /** 供测试断言降级前后状态对象相等。 */
    public boolean stateUnchanged(Object before, Object after) {
        return Objects.equals(before, after);
    }
}
