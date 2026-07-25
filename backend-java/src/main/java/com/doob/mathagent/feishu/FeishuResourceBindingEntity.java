package com.doob.mathagent.feishu;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
/** Durable link between one tenant resource and the user OAuth credential that may read it. */
@TableName("feishu_resource_binding")
public class FeishuResourceBindingEntity { @TableId private Long id; private String tenantId; private String documentId; private String subjectId; private String credentialId; private LocalDateTime createdAt; private LocalDateTime updatedAt;
 public Long getId(){return id;} public void setId(Long v){id=v;} public String getTenantId(){return tenantId;} public void setTenantId(String v){tenantId=v;} public String getDocumentId(){return documentId;} public void setDocumentId(String v){documentId=v;} public String getSubjectId(){return subjectId;} public void setSubjectId(String v){subjectId=v;} public String getCredentialId(){return credentialId;} public void setCredentialId(String v){credentialId=v;} public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;} public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;} }
