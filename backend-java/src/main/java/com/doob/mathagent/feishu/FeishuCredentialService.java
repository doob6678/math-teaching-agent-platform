package com.doob.mathagent.feishu;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Owns credential encryption, tenant/user lookup, and expiry transitions. */
@Service
public class FeishuCredentialService {
    private final FeishuCredentialMapper mapper;
    private final FeishuCredentialCipher cipher;
    public FeishuCredentialService(FeishuCredentialMapper mapper, FeishuCredentialCipher cipher) {
        this.mapper = mapper; this.cipher = cipher;
    }
    public FeishuCredential save(String tenantId, String subjectId, String access, String refresh, Instant expiresAt) {
        if (tenantId == null || tenantId.isBlank() || subjectId == null || subjectId.isBlank()) throw new IllegalArgumentException("tenant and subject are required");
        FeishuCredentialEntity row = mapper.findActive(tenantId, subjectId);
        if (row == null) { row = new FeishuCredentialEntity(); row.setCredentialId(UUID.randomUUID().toString()); row.setTenantId(tenantId); row.setSubjectId(subjectId); row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC)); }
        row.setAccessTokenCiphertext(cipher.encrypt(access)); row.setRefreshTokenCiphertext(refresh == null ? null : cipher.encrypt(refresh));
        row.setExpiresAt(expiresAt == null ? null : LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)); row.setStatus("ACTIVE"); row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        if (row.getId() == null) mapper.insert(row); else mapper.updateById(row);
        return read(row);
    }
    public FeishuCredential findActive(String tenantId, String subjectId) { FeishuCredentialEntity row=mapper.findActive(tenantId, subjectId); return row==null?null:read(row); }
    public FeishuCredential findActiveById(String tenantId, String credentialId) { FeishuCredentialEntity row=mapper.findActiveById(tenantId, credentialId); return row==null?null:read(row); }
    public void markExpired(String tenantId, String subjectId) { FeishuCredentialEntity row=mapper.findActive(tenantId,subjectId); if(row!=null){row.setStatus("EXPIRED");row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));mapper.updateById(row);} }
    private FeishuCredential read(FeishuCredentialEntity row) { return new FeishuCredential(row.getCredentialId(),row.getTenantId(),row.getSubjectId(),cipher.decrypt(row.getAccessTokenCiphertext()),row.getRefreshTokenCiphertext()==null?null:cipher.decrypt(row.getRefreshTokenCiphertext()),row.getExpiresAt()==null?null:row.getExpiresAt().toInstant(ZoneOffset.UTC)); }
}
