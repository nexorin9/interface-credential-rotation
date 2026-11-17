package com.med.icr.model;

import com.med.icr.store.ModelCallDao;
import com.med.icr.util.Masking;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 每次模型调用留可核对元数据：模型名 / host / 请求 id / 输入条数 / 时间窗 / 耗时 / 是否降级。 */
public final class ModelAudit {
    private final ModelCallDao dao;
    private final ModelConfig config;

    public ModelAudit(ModelCallDao dao, ModelConfig config) {
        this.dao = dao;
        this.config = config;
    }

    public void record(ModelRequest request, Instant windowStart, Instant windowEnd,
                       long elapsedMillis, boolean degraded, String failureClass,
                       String conclusionSummary, String requestId) {
        String host;
        try { host = URI.create(config.baseUrl()).getHost(); }
        catch (RuntimeException e) { host = "-"; }
        dao.write(new ModelCallDao.Entry(config.modelName(), host,
                requestId == null ? UUID.nameUUIDFromBytes(("icr-" + windowStart + "-" + request.events().size())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString() : requestId,
                request.events().size(), windowStart, windowEnd, elapsedMillis, degraded,
                failureClass, Masking.apply(conclusionSummary), Instant.now()));
    }

    public List<ModelCallDao.Entry> export(Instant from, Instant to) {
        return dao.findByWindow(from, to);
    }
}
