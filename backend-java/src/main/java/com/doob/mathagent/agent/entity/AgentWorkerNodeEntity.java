package com.doob.mathagent.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** MyBatis mapping for the durable Agent Worker registry. */
@TableName("agent_worker_node")
public class AgentWorkerNodeEntity {
    @TableId private String workerId;
    private String workerVersion;
    private String supportedAgentsJson;
    private Integer maxConcurrency;
    private Integer currentLoad;
    private String status;
    private Instant lastHeartbeatAt;
    private Long completedTaskCount;
    private Long failedTaskCount;
    private String lastErrorSummary;
    public String getWorkerId(){ return workerId; } public void setWorkerId(String value){ workerId=value; }
    public String getWorkerVersion(){ return workerVersion; } public void setWorkerVersion(String value){ workerVersion=value; }
    public String getSupportedAgentsJson(){ return supportedAgentsJson; } public void setSupportedAgentsJson(String value){ supportedAgentsJson=value; }
    public Integer getMaxConcurrency(){ return maxConcurrency; } public void setMaxConcurrency(Integer value){ maxConcurrency=value; }
    public Integer getCurrentLoad(){ return currentLoad; } public void setCurrentLoad(Integer value){ currentLoad=value; }
    public String getStatus(){ return status; } public void setStatus(String value){ status=value; }
    public Instant getLastHeartbeatAt(){ return lastHeartbeatAt; } public void setLastHeartbeatAt(Instant value){ lastHeartbeatAt=value; }
    public Long getCompletedTaskCount(){ return completedTaskCount; } public void setCompletedTaskCount(Long value){ completedTaskCount=value; }
    public Long getFailedTaskCount(){ return failedTaskCount; } public void setFailedTaskCount(Long value){ failedTaskCount=value; }
    public String getLastErrorSummary(){ return lastErrorSummary; } public void setLastErrorSummary(String value){ lastErrorSummary=value; }
}
