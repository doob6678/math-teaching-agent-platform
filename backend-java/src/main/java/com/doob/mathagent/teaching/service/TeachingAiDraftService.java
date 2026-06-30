package com.doob.mathagent.teaching.service;

import com.doob.mathagent.agent.service.AiChatGateway;
import com.doob.mathagent.agent.service.AiChatRequest;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.memory.vo.StudentMemoryResponse;
import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * AI drafting service for the teaching DAG.
 */
@Service
public class TeachingAiDraftService {

    private final AiChatGateway aiChatGateway;
    private final AiProviderCatalog providerCatalog;

    /**
     * Creates the teaching AI draft service.
     *
     * @param aiChatGateway real model gateway
     * @param providerCatalog enabled provider catalog
     */
    public TeachingAiDraftService(AiChatGateway aiChatGateway, AiProviderCatalog providerCatalog) {
        this.aiChatGateway = aiChatGateway;
        this.providerCatalog = providerCatalog;
    }

    /**
     * Calls enabled providers in backend fallback order and returns usable teaching content.
     *
     * @param request teaching task request
     * @param evidence retrieved evidence
     * @param memoryResponse student memory reuse decision
     * @return AI draft metadata and content
     */
    public TeachingTaskResponse.AiDraft draft(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse) {
        List<AiProviderCatalog.Provider> providers = providerCatalog.enabledProviders();
        if (providers.isEmpty()) {
            return new TeachingTaskResponse.AiDraft(false, "", "", 0, 0, 0, "", "No enabled AI provider.");
        }
        RuntimeException lastFailure = null;
        for (AiProviderCatalog.Provider provider : providers) {
            try {
                AiChatResult result = aiChatGateway.call(new AiChatRequest(
                        provider.name(),
                        provider.chatModel(),
                        "CoursewareAgent",
                        prompt(request, evidence, memoryResponse),
                        evidenceRefs(evidence)));
                return new TeachingTaskResponse.AiDraft(
                        true,
                        result.providerName(),
                        result.modelCode(),
                        result.promptTokens(),
                        result.completionTokens(),
                        result.totalTokens(),
                        result.generatedContent(),
                        result.safeMessage());
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
        }
        return new TeachingTaskResponse.AiDraft(
                true,
                "",
                "",
                0,
                0,
                0,
                "",
                "AI provider failed: " + (lastFailure == null ? "unknown" : lastFailure.getClass().getSimpleName()));
    }

    /**
     * Returns a disabled service for focused tests that do not configure real provider credentials.
     *
     * @return disabled draft service
     */
    public static TeachingAiDraftService disabled() {
        return new TeachingAiDraftService(
                request -> {
                    throw new IllegalStateException("Live AI gateway is not configured");
                },
                new AiProviderCatalog(new com.doob.mathagent.infrastructure.ai.AiProviderProperties()));
    }

    /**
     * Builds a classroom-ready prompt from real task data and retrieved evidence.
     */
    private static String prompt(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse) {
        return """
                你是高中数学备课智能体。基于给定证据生成可直接放入讲义的内容。
                必须输出四段：教师讲解、学生提示、关键知识点、后续互动问题。
                不要写“作为AI”，不要编造没有给出的来源。
                学习目标：%s
                题目：%s
                记忆复用：%s
                检索证据：%s
                """.formatted(
                request.learningGoal(),
                request.questionText(),
                memoryResponse.reused() ? memoryResponse.answer() : memoryResponse.reason(),
                evidence.stream().map(TeachingAiDraftService::evidenceLine).toList());
    }

    /**
     * Converts evidence rows to compact references passed to the model gateway.
     */
    private static List<String> evidenceRefs(List<TeachingEvidence> evidence) {
        return evidence.stream()
                .map(item -> item.sourceScope() + ":" + item.sourceTitle() + ":" + item.chunkId())
                .toList();
    }

    /**
     * Converts one evidence row to prompt text.
     */
    private static String evidenceLine(TeachingEvidence evidence) {
        return evidence.sourceScope() + "/" + evidence.sourceTitle() + "/p." + evidence.pageNo() + ": " + evidence.snippet();
    }
}
