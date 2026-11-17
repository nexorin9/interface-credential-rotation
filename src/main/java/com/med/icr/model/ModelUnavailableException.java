package com.med.icr.model;

/** 模型不可用 / 输出不合规：统一触发规则降级，不猜值、不补默认值。 */
public class ModelUnavailableException extends RuntimeException {
    public static final String TIMEOUT = "MODEL_TIMEOUT";
    public static final String SERVER_ERROR = "MODEL_HTTP_5XX";
    public static final String RATE_LIMITED = "MODEL_HTTP_429";
    public static final String CONFIG_ERROR = "MODEL_CONFIG_ERROR";
    public static final String UNREACHABLE = "MODEL_UNREACHABLE";
    public static final String BAD_OUTPUT = "MODEL_BAD_OUTPUT";

    public final String code;

    public ModelUnavailableException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }
}
