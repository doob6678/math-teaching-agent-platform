package com.doob.mathagent.feishu;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Durable tenant/user-scoped encrypted Feishu token row. */
@TableName("feishu_user_credential")
public class FeishuCredentialEntity {
    @TableId private Long id;
    private String credentialId;
    private String tenantId;
    private String subjectId;
    private String accessTokenCiphertext;
    private String refreshTokenCiphertext;
    private LocalDateTime expiresAt;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getCredentialId(){return credentialId;} public void setCredentialId(String v){credentialId=v;}
    public String getTenantId(){return tenantId;} public void setTenantId(String v){tenantId=v;}
    public String getSubjectId(){return subjectId;} public void setSubjectId(String v){subjectId=v;}
    public String getAccessTokenCiphertext(){return accessTokenCiphertext;} public void setAccessTokenCiphertext(String v){accessTokenCiphertext=v;}
    public String getRefreshTokenCiphertext(){return refreshTokenCiphertext;} public void setRefreshTokenCiphertext(String v){refreshTokenCiphertext=v;}
    public LocalDateTime getExpiresAt(){return expiresAt;} public void setExpiresAt(LocalDateTime v){expiresAt=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
