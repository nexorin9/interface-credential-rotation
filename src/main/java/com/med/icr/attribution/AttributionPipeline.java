package com.med.icr.attribution;

import com.med.icr.domain.Credential;
import com.med.icr.domain.FailureEvent;
import com.med.icr.domain.RootCauseCard;
import com.med.icr.model.ModelRequest;

import java.util.List;

/** 业务层只依赖此接口：规则版 / 模型版可互换。 */
public interface AttributionPipeline {
    record Context(List<FailureEvent> events, List<Credential> ledger, boolean modelUsed,
                   ModelRequest request, int maxInputEvents) {
        public static Context of(List<FailureEvent> events, List<Credential> ledger, boolean modelUsed) {
            return new Context(events, ledger, modelUsed, null, 200);
        }
    }
    record Result(RootCauseCard card, boolean degraded, String errorCode, boolean compliant) {}

    Result run(Context context);
}
