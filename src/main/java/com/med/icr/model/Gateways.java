package com.med.icr.model;

/** 网关装配点：业务层只经此处取网关，测试可注入本地替身。 */
public final class Gateways {
    private static volatile ModelGateway injected;
    private Gateways() {}

    public static void inject(ModelGateway gateway) { injected = gateway; }
    public static void reset() { injected = null; }

    public static ModelGateway get() {
        ModelGateway current = injected;
        if (current != null) return current;
        return new OpenAiCompatibleGateway(ModelConfig.fromEnvironment());
    }
}
