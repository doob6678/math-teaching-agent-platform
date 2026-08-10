package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.vo.AgentRegistryResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Projects the executable policy catalog into marketplace cards after filtering by the backend-resolved subject.
 * The registry deliberately has no mutation endpoint: adding an agent is a reviewed server-side policy change.
 */
@Service
public class AgentRegistryService {

    /** Returns only cards this subject could subsequently plan and execute. */
    public AgentRegistryResponse visibleAgents(RequestSubject subject) {
        RequestSubject normalized = subject.normalize();
        List<AgentRegistryResponse.Item> cards = AgentRunPolicy.definitions().stream()
                .filter(definition -> definition.allowedRoles().contains(normalized.subjectType()))
                .map(this::card)
                .toList();
        return new AgentRegistryResponse(cards);
    }

    /** Keeps display content beside its policy code, preventing the frontend from inventing a contract. */
    private AgentRegistryResponse.Item card(AgentRunPolicy.AgentDefinition definition) {
        AgentPresentation presentation = AgentPresentation.forCode(definition.code());
        return new AgentRegistryResponse.Item(
                definition.code(), presentation.name(), presentation.category(), presentation.description(),
                definition.allowedToolScopes().stream().sorted().toList(),
                definition.allowedDataScopes().stream().sorted().toList(),
                presentation.inputHint(), presentation.outputArtifactType());
    }

    /** Product labels are deliberately a closed mapping; an unknown reviewed policy entry is still safe to display. */
    private record AgentPresentation(String name, String category, String description, String inputHint, String outputArtifactType) {
        private static AgentPresentation forCode(String code) {
            return switch (code) {
                case "SupervisorAgent" -> new AgentPresentation("主智能体", "编排", "拆解任务、选择专业智能体并汇总经审校的产物。", "目标、约束、已有产物", "TASK_GRAPH");
                case "KnowledgeRetrievalAgent" -> new AgentPresentation("知识检索智能体", "检索", "改写查询、检索、重排并输出带来源的证据包。", "问题或知识点", "EVIDENCE_PACK");
                case "DocumentWriterAgent" -> new AgentPresentation("文档写作智能体", "写作", "依据已授权证据起草结构化 Markdown/LaTeX 文档。", "写作目标、证据包", "DOCUMENT_DRAFT");
                case "QualityCheckAgent" -> new AgentPresentation("质量审校智能体", "审校", "检查引用、答案泄露、格式和任务约束。", "草稿、证据包", "REVIEW_FINDINGS");
                case "HandoutFormatterAgent" -> new AgentPresentation("讲义排版智能体", "排版", "生成教师版、学生版或投屏版交付物。", "通过审校的文档草稿", "DELIVERY");
                case "CoursewareAgent" -> new AgentPresentation("课件生成智能体", "写作", "生成课堂讲义、课件与版本化教学内容。", "教学目标、题目、证据", "COURSEWARE_DRAFT");
                case "TeacherAssistantAgent" -> new AgentPresentation("教师助理智能体", "教学", "支持教师资料检索、课堂任务与学生版内容处理。", "教师任务与上下文", "TEACHING_ASSISTANCE");
                default -> new AgentPresentation(code, "通用", "受后端策略治理的专业智能体。", "任务说明", "AGENT_RESULT");
            };
        }
    }
}
