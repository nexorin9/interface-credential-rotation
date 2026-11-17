package com.med.icr.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 对接凭据台账行（只含元数据，永不包含明文）。不可变对象。 */
public final class Credential {
    private final String credentialId;
    private final String channelCode;
    private final CredentialType credentialType;
    private final Instant expiresAt;
    private final String ownerDept;
    private final String owner;
    private final String rotationEntry;
    private final Instant lastVerifiedAt;
    private final String fingerprint;
    private final CredentialState state;
    private final List<String> channelCodes;
    private final List<String> downstreamSystems;

    public Credential(String credentialId, String channelCode, CredentialType credentialType,
                      Instant expiresAt, String ownerDept, String owner, String rotationEntry,
                      Instant lastVerifiedAt, String fingerprint, CredentialState state,
                      List<String> channelCodes, List<String> downstreamSystems) {
        this.credentialId = credentialId;
        this.channelCode = channelCode;
        this.credentialType = credentialType;
        this.expiresAt = expiresAt;
        this.ownerDept = ownerDept;
        this.owner = owner;
        this.rotationEntry = rotationEntry;
        this.lastVerifiedAt = lastVerifiedAt;
        this.fingerprint = fingerprint;
        this.state = state == null ? CredentialState.ACTIVE : state;
        List<String> channels = new ArrayList<>();
        if (channelCodes != null) channels.addAll(channelCodes);
        if (channels.isEmpty() && channelCode != null) channels.add(channelCode);
        this.channelCodes = Collections.unmodifiableList(channels);
        this.downstreamSystems = downstreamSystems == null
                ? List.of() : Collections.unmodifiableList(new ArrayList<>(downstreamSystems));
    }

    public static Credential minimal(String id, String channel, CredentialType type, Instant expiresAt) {
        return new Credential(id, channel, type, expiresAt, "信息科", "集成运维岗", "换证入口", null, null,
                CredentialState.ACTIVE, null, null);
    }

    public String credentialId() { return credentialId; }
    public String channelCode() { return channelCode; }
    public CredentialType credentialType() { return credentialType; }
    public Instant expiresAt() { return expiresAt; }
    public String ownerDept() { return ownerDept; }
    public String owner() { return owner; }
    public String rotationEntry() { return rotationEntry; }
    public Instant lastVerifiedAt() { return lastVerifiedAt; }
    public String fingerprint() { return fingerprint; }
    public CredentialState state() { return state; }
    public List<String> channelCodes() { return channelCodes; }
    public List<String> downstreamSystems() { return downstreamSystems; }
    public int channelCount() { return channelCodes.size(); }

    public Credential withState(CredentialState newState) {
        return new Credential(credentialId, channelCode, credentialType, expiresAt, ownerDept, owner,
                rotationEntry, lastVerifiedAt, fingerprint, newState, channelCodes, downstreamSystems);
    }
    public Credential withFingerprint(String fp) {
        return new Credential(credentialId, channelCode, credentialType, expiresAt, ownerDept, owner,
                rotationEntry, lastVerifiedAt, fp, state, channelCodes, downstreamSystems);
    }
    public Credential withLastVerifiedAt(Instant instant) {
        return new Credential(credentialId, channelCode, credentialType, expiresAt, ownerDept, owner,
                rotationEntry, instant, fingerprint, state, channelCodes, downstreamSystems);
    }
    public Credential withChannels(List<String> channels, List<String> downstream) {
        return new Credential(credentialId, channelCode, credentialType, expiresAt, ownerDept, owner,
                rotationEntry, lastVerifiedAt, fingerprint, state, channels, downstream);
    }
}
