package com.doob.mathagent.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** Durable broker-dispatch state for one versioned Agent Worker task generation. */
@TableName("agent_worker_task_outbox_event")
public class AgentWorkerTaskOutboxEventEntity {
    @TableId private String eventId; private String taskId; private Integer dispatchVersion; private String agentCode;
    private String stageCode; private String status; private Integer publishAttempt; private Instant nextAttemptAt;
    private Instant publishLeaseUntil; private String lockedBy; private String lastError; private Instant createdAt; private Instant publishedAt;
    public String getEventId(){return eventId;} public void setEventId(String v){eventId=v;} public String getTaskId(){return taskId;} public void setTaskId(String v){taskId=v;} public Integer getDispatchVersion(){return dispatchVersion;} public void setDispatchVersion(Integer v){dispatchVersion=v;} public String getAgentCode(){return agentCode;} public void setAgentCode(String v){agentCode=v;} public String getStageCode(){return stageCode;} public void setStageCode(String v){stageCode=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;} public Integer getPublishAttempt(){return publishAttempt;} public void setPublishAttempt(Integer v){publishAttempt=v;} public Instant getNextAttemptAt(){return nextAttemptAt;} public void setNextAttemptAt(Instant v){nextAttemptAt=v;} public Instant getPublishLeaseUntil(){return publishLeaseUntil;} public void setPublishLeaseUntil(Instant v){publishLeaseUntil=v;} public String getLockedBy(){return lockedBy;} public void setLockedBy(String v){lockedBy=v;} public String getLastError(){return lastError;} public void setLastError(String v){lastError=v;} public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;} public Instant getPublishedAt(){return publishedAt;} public void setPublishedAt(Instant v){publishedAt=v;}
}
