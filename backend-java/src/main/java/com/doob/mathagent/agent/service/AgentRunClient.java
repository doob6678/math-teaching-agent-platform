package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.AgentRunExecuteRequest;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;

/**
 * Java 到 Python 通用 AI 执行边界。
 *
 * <p>实现必须调用受 worker key 保护的 Python 协议；Java provider gateway 不能作为回退实现。</p>
 */
public interface AgentRunClient {

    /** 执行一项已经通过 Java 身份、策略和预算检查的运行。 */
    Result execute(String traceId, AgentRunExecuteRequest request, AgentRunPlanResponse plan);

    /** 经 Java schema 校验后的 Python 结果投影。 */
    record Result(
            String providerName,
            String modelCode,
            AgentRunExecuteResponse.TokenUsage actualUsage,
            String message,
            String generatedContent,
            double actualCost,
            boolean costKnown) {
    }
}
