package com.doob.mathagent.teaching.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** MyBatis row for a reliably publishable lecture task event. */
@TableName("lecture_task_outbox_event")
public class LectureTaskOutboxEventEntity {
    @TableId private String eventId;
    private String taskId;
    private String eventType;
    private String status;
    private Instant createdAt;
    private Instant publishedAt;
    public String getEventId() { return eventId; } public void setEventId(String value) { eventId = value; }
    public String getTaskId() { return taskId; } public void setTaskId(String value) { taskId = value; }
    public String getEventType() { return eventType; } public void setEventType(String value) { eventType = value; }
    public String getStatus() { return status; } public void setStatus(String value) { status = value; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getPublishedAt() { return publishedAt; } public void setPublishedAt(Instant value) { publishedAt = value; }
}
