package com.med.icr.ingest;

/** 事件流客户端错误：每类失败映射到不同错误码，绝不静默返回空列表。 */
public class EsbClientException extends RuntimeException {
    public static final String AUTH_FAILED = "ESB_AUTH_FAILED";
    public static final String NOT_FOUND = "ESB_NOT_FOUND";
    public static final String SERVER_ERROR = "ESB_SERVER_ERROR";
    public static final String UNREACHABLE = "ESB_UNREACHABLE";
    public static final String BAD_STRUCTURE = "ESB_BAD_STRUCTURE";

    public final String code;
    public final int status;

    public EsbClientException(String code, int status, String message) {
        super(code + ": " + message);
        this.code = code;
        this.status = status;
    }
}
