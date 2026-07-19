package com.doob.mathagent.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** MyBatis mapping for lease-protected distributed Agent Worker tasks. */
@TableName("agent_worker_task")
public class AgentWorkerTaskEntity {
    @TableId private String taskId; private String workflowId; private String tenantId; private String agentCode; private String stageCode; private String status; private Integer attempt; private String leaseToken; private Instant leaseExpiresAt; private String workerId; private String requestJson; private String errorSummary; private Instant createdAt; private Instant updatedAt;
    public String getTaskId(){return taskId;} public void setTaskId(String v){taskId=v;} public String getWorkflowId(){return workflowId;} public void setWorkflowId(String v){workflowId=v;} public String getTenantId(){return tenantId;} public void setTenantId(String v){tenantId=v;} public String getAgentCode(){return agentCode;} public void setAgentCode(String v){agentCode=v;} public String getStageCode(){return stageCode;} public void setStageCode(String v){stageCode=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;} public Integer getAttempt(){return attempt;} public void setAttempt(Integer v){attempt=v;} public String getLeaseToken(){return leaseToken;} public void setLeaseToken(String v){leaseToken=v;} public Instant getLeaseExpiresAt(){return leaseExpiresAt;} public void setLeaseExpiresAt(Instant v){leaseExpiresAt=v;} public String getWorkerId(){return workerId;} public void setWorkerId(String v){workerId=v;} public String getRequestJson(){return requestJson;} public void setRequestJson(String v){requestJson=v;} public String getErrorSummary(){return errorSummary;} public void setErrorSummary(String v){errorSummary=v;} public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;} public Instant getUpdatedAt(){return updatedAt;} public void setUpdatedAt(Instant v){updatedAt=v;}
}
