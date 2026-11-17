package com.med.icr.model;

import java.util.List;

/** 模型输出：自有类型，字段固定，不含任何厂商 SDK 类型。 */
public record ModelResponse(String rootCause, String credentialId, double confidence,
                            List<String> evidenceRefs, String suggestedAction, String severity) {}
