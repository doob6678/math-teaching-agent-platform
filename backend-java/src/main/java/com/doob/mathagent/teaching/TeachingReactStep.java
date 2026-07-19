package com.doob.mathagent.teaching;

/**
 * ReAct 轨迹步骤。
 *
 * @param phase 阶段类型：THOUGHT、ACTION、OBSERVATION、ANSWER。
 * @param content 当前阶段的自然语言说明。
 * @param toolName ACTION 阶段调用的工具名；非 ACTION 阶段可为空。
 */
public record TeachingReactStep(String phase, String content, String toolName) {
}
