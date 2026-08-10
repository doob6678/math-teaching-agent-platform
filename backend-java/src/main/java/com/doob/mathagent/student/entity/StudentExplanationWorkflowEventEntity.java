package com.doob.mathagent.student.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** MyBatis entity for one append-only public explanation workflow event. */
@TableName("student_explanation_workflow_event")
public class StudentExplanationWorkflowEventEntity {

    @TableId(type = IdType.AUTO)
    private Long eventId;
    private String runId;
    private String eventName;
    private String eventJson;
    private LocalDateTime createdAt;

    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }
    public String getEventJson() { return eventJson; }
    public void setEventJson(String eventJson) { this.eventJson = eventJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
