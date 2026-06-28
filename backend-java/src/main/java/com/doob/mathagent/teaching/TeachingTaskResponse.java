package com.doob.mathagent.teaching;

import java.util.List;

/**
 * 教学任务响应。
 *
 * @param taskId 后端任务 ID，前端可保存到 localStorage，离开页面后继续查询。
 * @param clientRequestId 前端幂等请求号。
 * @param tenantId 租户 ID，用于说明任务归属。
 * @param subjectType 主体类型，用于权限审计。
 * @param subjectId 主体 ID，用于私有任务隔离。
 * @param status 任务状态。
 * @param questionText 原始题目或学习问题。
 * @param learningGoal 用户学习目标。
 * @param nodes 固定 DAG 节点执行结果。
 * @param reactTrace 解题 ReAct 轨迹。
 * @param evidence 使用的证据列表。
 * @param handoutLatex LaTeX 讲义草稿。
 * @param interactiveSuggestions 后续交互建议。
 * @param errorMessage 失败原因；成功时为空。
 */
public record TeachingTaskResponse(
        String taskId,
        String clientRequestId,
        String tenantId,
        String subjectType,
        String subjectId,
        TeachingTaskStatus status,
        String questionText,
        String learningGoal,
        List<TeachingWorkflowNode> nodes,
        List<TeachingReactStep> reactTrace,
        List<TeachingEvidence> evidence,
        String handoutLatex,
        List<String> interactiveSuggestions,
        String errorMessage) {
}
