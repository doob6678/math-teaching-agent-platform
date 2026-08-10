package com.doob.mathagent.feishu;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
/** Creates and resolves resource-level user authorization links without exposing token material. */
@Service public class FeishuResourceBindingService {
 private static final Logger LOGGER=LoggerFactory.getLogger(FeishuResourceBindingService.class);
 /** A single retry addresses transient database contention without hiding a durable authorization failure. */
 private static final int MAX_BIND_ATTEMPTS=2;
 private final FeishuResourceBindingMapper mapper; private final FeishuCredentialService credentials;
 public FeishuResourceBindingService(FeishuResourceBindingMapper mapper,FeishuCredentialService credentials){this.mapper=mapper;this.credentials=credentials;}
 public void bind(String tenantId,String documentId,String subjectId){
  FeishuCredential c=credentials.findActive(tenantId,subjectId);if(c==null)throw new IllegalArgumentException("AUTH_REQUIRED");
  for(int attempt=1;attempt<=MAX_BIND_ATTEMPTS;attempt++) try { bindOnce(tenantId,documentId,subjectId,c);return; } catch(DataAccessException exception) {
   LOGGER.warn("feishu_resource_binding_failed tenantId={} documentId={} attempt={}",tenantId,documentId,attempt,exception);
   if(attempt==MAX_BIND_ATTEMPTS)throw new IllegalStateException("FEISHU_RESOURCE_BINDING_FAILED",exception);
  }
 }
 private void bindOnce(String tenantId,String documentId,String subjectId,FeishuCredential credential){FeishuResourceBindingEntity row=mapper.find(tenantId,documentId);if(row==null){row=new FeishuResourceBindingEntity();row.setTenantId(tenantId);row.setDocumentId(documentId);row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));}row.setSubjectId(subjectId);row.setCredentialId(credential.credentialId());row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));if(row.getId()==null)mapper.insert(row);else mapper.updateById(row);}
 public String subjectId(String tenantId,String documentId){FeishuResourceBindingEntity row=mapper.find(tenantId,documentId);return row==null?null:row.getSubjectId();}
}
