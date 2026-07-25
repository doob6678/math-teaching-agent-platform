package com.doob.mathagent.feishu;

import java.time.Instant;

/** Decrypted credential value held only inside the backend sync call. */
public record FeishuCredential(String credentialId, String tenantId, String subjectId,
                               String accessToken, String refreshToken, Instant expiresAt) {
    public boolean expired(Instant now) { return expiresAt == null || !expiresAt.isAfter(now.plusSeconds(30)); }
}
