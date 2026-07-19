package com.doob.mathagent.teaching;

/**
 * 教学 DAG 节点执行记录。
 *
 * @param code 节点编码，稳定标识 DAG 中的步骤。
 * @param name 节点中文名称，用于前端展示。
 * @param status 节点状态，当前最小实现为 completed。
 * @param summary 节点输出摘要，说明该节点做了什么。
 */
public record TeachingWorkflowNode(String code, String name, String status, String summary) {
}
