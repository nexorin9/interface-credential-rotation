package com.med.icr.util;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 全局唯一的 Jackson 映射器：所有外发报文、样例与报告共用一套配置。 */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    private Json() {}
}
