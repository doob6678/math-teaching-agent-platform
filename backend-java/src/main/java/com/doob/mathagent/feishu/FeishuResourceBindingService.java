package com.doob.mathagent.feishu;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
/** Creates and resolves resource-level user authorization links without exposing token material. */
@Service public class FeishuResourceBindingService { private final FeishuResourceBindingMapper mapper; private final FeishuCredentialService credentials;
 public FeishuResourceBindingService(FeishuResourceBindingMapper mapper,FeishuCredentialService credentials){this.mapper=mapper;this.credentials=credentials;}
 public void bind(String tenantId,String documentId,String subjectId){FeishuCredential c=credentials.findActive(tenantId,subjectId);if(c==null)throw new IllegalArgumentException("AUTH_REQUIRED");FeishuResourceBindingEntity row=mapper.find(tenantId,documentId);if(row==null){row=new FeishuResourceBindingEntity();row.setTenantId(tenantId);row.setDocumentId(documentId);row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));}row.setSubjectId(subjectId);row.setCredentialId(c.credentialId());row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));if(row.getId()==null)mapper.insert(row);else mapper.updateById(row);}
 public String subjectId(String tenantId,String documentId){FeishuResourceBindingEntity row=mapper.find(tenantId,documentId);return row==null?null:row.getSubjectId();}
}
