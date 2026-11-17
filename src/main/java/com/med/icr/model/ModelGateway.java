package com.med.icr.model;

/** 单一可替换网关：业务层只依赖本项目自有类型。 */
public interface ModelGateway {
    ModelResponse infer(ModelRequest request) throws ModelUnavailableException;
    default String name() { return getClass().getSimpleName(); }
}
