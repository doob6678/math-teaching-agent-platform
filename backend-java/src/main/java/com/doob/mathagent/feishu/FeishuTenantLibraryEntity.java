package com.doob.mathagent.feishu;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Durable tenant -> Feishu root folder mapping that keeps auto-created libraries idempotent. */
@TableName("feishu_tenant_library")
public class FeishuTenantLibraryEntity {
    @TableId private Long id;
    private String tenantId;
    private String folderName;
    private String rootFolderToken;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getTenantId(){return tenantId;} public void setTenantId(String v){tenantId=v;}
    public String getFolderName(){return folderName;} public void setFolderName(String v){folderName=v;}
    public String getRootFolderToken(){return rootFolderToken;} public void setRootFolderToken(String v){rootFolderToken=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
