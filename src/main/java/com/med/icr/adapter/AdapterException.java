package com.med.icr.adapter;

/** 外发与探测统一错误码：鉴权 / 服务端 / 不可达 / 明文字段 / 写回失败。 */
public class AdapterException extends RuntimeException {
    public static final String AUTH_FAILED = "ADAPTER_AUTH_FAILED";
    public static final String SERVER_ERROR = "ADAPTER_SERVER_ERROR";
    public static final String UNREACHABLE = "ADAPTER_UNREACHABLE";
    public static final String BAD_STRUCTURE = "ADAPTER_BAD_STRUCTURE";
    public static final String PLAINTEXT_FIELD = "LEDGER_PLAINTEXT_FIELD";
    public static final String WRITE_FAILED = "WRITE_BACK_FAILED";

    public final String code;
    public final int status;

    public AdapterException(String code, int status, String message) {
        super(code + ": " + message);
        this.code = code;
        this.status = status;
    }
}
