package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.util.List;

/** 已授权教学任务到 Python 讲义图的唯一执行边界。 */
public interface TeachingHandoutAiClient {

    TeachingTaskResponse.AiDraft execute(
            String taskId, TeachingTaskRequest request, List<TeachingEvidence> evidence);
}
