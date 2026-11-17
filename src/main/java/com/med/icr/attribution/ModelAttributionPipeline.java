package com.med.icr.attribution;

import com.med.icr.model.ModelGateway;
import com.med.icr.model.ModelResponse;
import com.med.icr.model.ModelUnavailableException;

/** 模型版管道：model 段只经单一 ModelGateway，业务层不接触厂商协议。 */
public final class ModelAttributionPipeline extends AbstractAttributionPipeline {
    private final ModelGateway gateway;

    public ModelAttributionPipeline(ModelGateway gateway) {
        this(new RuleAttributor(), gateway);
    }

    public ModelAttributionPipeline(RuleAttributor attributor, ModelGateway gateway) {
        super(attributor);
        this.gateway = gateway;
    }

    @Override
    protected ModelResponse model(Context ctx) throws ModelUnavailableException {
        if (ctx.request() == null) {
            throw new ModelUnavailableException(ModelUnavailableException.BAD_OUTPUT, "preprocess 未组装请求");
        }
        return gateway.infer(ctx.request());
    }
}
