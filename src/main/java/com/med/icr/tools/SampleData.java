package com.med.icr.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.med.icr.domain.Credential;
import com.med.icr.domain.CredentialType;
import com.med.icr.domain.FailureEvent;
import com.med.icr.ingest.FailureEventParser;
import com.med.icr.util.Json;
import com.med.icr.util.Times;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 样例读取公共入口：CLI、桌面、对比基准共用同一套字段映射，避免两处口径漂移。 */
public final class SampleData {
    private SampleData() {}

    public static List<Credential> readLedger(Path file) throws IOException {
        JsonNode root = Json.MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        List<Credential> out = new ArrayList<>();
        for (JsonNode n : root) {
            List<String> channels = new ArrayList<>();
            for (JsonNode c : n.path("channel_codes")) channels.add(c.asText());
            List<String> downstream = new ArrayList<>();
            for (JsonNode d : n.path("downstream_systems")) downstream.add(d.asText());
            String exp = n.path("expires_at").asText(null);
            String verified = n.path("last_verified_at").asText(null);
            out.add(new Credential(n.path("credential_id").asText(), n.path("channel_code").asText(),
                    CredentialType.parse(n.path("credential_type").asText()),
                    exp == null ? null : Times.parse(exp), n.path("owner_dept").asText("信息科"),
                    n.path("owner").asText("集成运维岗"), n.path("rotation_entry").asText("换证入口"),
                    verified == null ? null : Times.parse(verified),
                    n.path("fingerprint").asText(null), null, channels, downstream));
        }
        return out;
    }

    public static List<FailureEvent> readEvents(Path file) throws IOException {
        return new FailureEventParser().parse(file).events();
    }
}
