package com.doob.mathagent.feishu;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** One batch-upload ledger row: idempotency (content hash) plus the durable Feishu file token. */
@TableName("feishu_handout_upload")
public class FeishuHandoutUploadEntity {
    @TableId private Long id;
    private String tenantId;
    private String subjectId;
    private String taskId;
    private String version;
    private String contentHash;
    private String fileName;
    private String fileToken;
    private String status;
    private String message;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getTenantId(){return tenantId;} public void setTenantId(String v){tenantId=v;}
    public String getSubjectId(){return subjectId;} public void setSubjectId(String v){subjectId=v;}
    public String getTaskId(){return taskId;} public void setTaskId(String v){taskId=v;}
    public String getVersion(){return version;} public void setVersion(String v){version=v;}
    public String getContentHash(){return contentHash;} public void setContentHash(String v){contentHash=v;}
    public String getFileName(){return fileName;} public void setFileName(String v){fileName=v;}
    public String getFileToken(){return fileToken;} public void setFileToken(String v){fileToken=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getMessage(){return message;} public void setMessage(String v){message=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
