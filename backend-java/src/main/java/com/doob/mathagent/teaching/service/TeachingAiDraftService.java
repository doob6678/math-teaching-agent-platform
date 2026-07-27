package com.doob.mathagent.teaching.service;

import com.doob.mathagent.agent.service.AiChatGateway;
import com.doob.mathagent.agent.service.AiChatRequest;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.text.FormulaMarkupSanitizer;
import com.doob.mathagent.knowledge.service.QuestionBankSearchText;
import com.doob.mathagent.memory.vo.StudentMemoryResponse;
import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

/**
 * AI drafting service for the teaching DAG.
 */
@Service
public class TeachingAiDraftService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** Caps the model-only copy while retaining enough geometry for textbook diagrams and formula labels. */
    private static final int MODEL_IMAGE_MAX_LONG_EDGE = 1536;
    /** Rejects unexpectedly large files before decoding them into heap memory. */
    private static final long MODEL_IMAGE_MAX_SOURCE_BYTES = 32L * 1024L * 1024L;
    /** OpenAI-compatible low-detail image requests use one fixed 512px overview budget. */
    private static final int LOW_DETAIL_IMAGE_TOKENS = 85;
    private static final int HIGH_DETAIL_BASE_TOKENS = 85;
    private static final int HIGH_DETAIL_TILE_TOKENS = 170;
    private static final int HIGH_DETAIL_TILE_EDGE = 512;
    private static final String MODEL_IMAGE_DETAIL = "low";
    private static final TeachingHandoutTemplateProfile DEFAULT_TEMPLATE =
            new TeachingHandoutTemplateService().resolve("default_standard");
    private static final Pattern STUDENT_FORBIDDEN_SECTION = Pattern.compile(
            "【(?:答案与评分点|参考答案|参考解析|评分标准|例题详解|完整解析|教师讲解|讲评主线|教师备注|板书设计)】[\\s\\S]*?(?=【|$)");
    private static final Pattern STUDENT_FORBIDDEN_LINE = Pattern.compile(
            "(?m)^.*(?:答案[：:]|答案为|参考答案|参考解析|评分点|评分标准|完整解析|解答如下|解：|因此答案为|故答案为).*$");
    private static final Pattern VISIBLE_WORKSPACE_LABEL = Pattern.compile(
            "(?:课堂作答区|作答区|我的解答|推导区|手写区|教师手写区|留白区|空白区|板书区|教师板书区)\\s*[：:]?");
    private static final Pattern VISIBLE_WORKSPACE_REFERENCE = Pattern.compile(
            "(?:写在|填写在|完成在|放在|留在)(?:课堂作答区|作答区|我的解答|推导区|手写区|教师手写区|留白区|空白区|板书区|教师板书区)");
    private static final Pattern INTERNAL_HANDOUT_LINE = Pattern.compile(
            "(?mi)^.*(?:MODEL_CALL|JSON_PARSE|\\btokens?\\b|模型健康|model health|debug|调试|JSON|页眉|页脚|(?:页面颜色|颜色规则|讲评色|练习色)|PDF\\s*(?:规则|排版|版式)|排版说明|版式要求|渲染引擎|页边距|虚线折叠|documentclass|usepackage|fancyhdr|pagestyle|begin\\{document}|end\\{document}|作为\\s*AI|as an AI|本页只保留|课堂任务|本讲任务|讲后自查|教师审校清单|横版讲解提纲|模板偏向|本讲更偏向|生成后保存|导出\\s*PDF|内部提示词|模型诊断|教师资料命中|板书步骤|飞书文档|图片无法读取|学生版不得|独立生成).*$");
    /** Visible placeholders indicate that the model did not ground a section in a real topic or question. */
    private static final Pattern CONTENT_PLACEHOLDER = Pattern.compile(
            "(?i)(?:知识点\\s*[0-9一二三四五六七八九十]+|题型\\s*[0-9一二三四五六七八九十]+|例题\\s*(?:待补充|占位|框架)|题目内容待补充|暂无真实题目资料|无法按原题号编写例题详解|示例待补充|待检索|待填写|\\{\\{[^}]+}})");
    private static final Pattern TASK_CONTROL_LINE = Pattern.compile(
            "(?mi)^.*(?:请依据教材.*组织讲义|给出教师资料命中|没有可用资料时|不得伪造来源|处理飞书文档|图片无法读取|学生版不得|内部提示词|模型诊断|验证.*讲解版|不从教师版截取|生成后保存|保存.*编辑|导出\\s*PDF|任务耗时|提示词|题目入口|讲评入口|题型入口|知识入口|审题提醒|模板|benchmark|synthetic-natural|量化评测|投票|工作流|智能体|子agent|子智能体).*$");
    /** Source question numbers are a durable cross-check that a long-form draft did not silently omit real rows. */
    private static final Pattern SOURCE_QUESTION_NUMBER = Pattern.compile("^\\s*(\\d{1,3})[.．、]");
    private static final List<String> PREFERRED_TOPIC_ANCHORS = List.of(
            "双曲线", "椭圆", "抛物线", "圆锥曲线", "导数", "函数", "数列", "概率",
            "统计", "三角函数", "平面向量", "空间向量", "立体几何", "直线", "圆", "排列组合");

    private final AiChatGateway aiChatGateway;
    private final AiProviderCatalog providerCatalog;
    private final TeachingAiDraftProperties aiDraftProperties;

    /**
     * Creates the teaching AI draft service.
     *
     * @param aiChatGateway real model gateway
     * @param providerCatalog enabled provider catalog
     * @param aiDraftProperties runtime retry policy
     */
    public TeachingAiDraftService(
            AiChatGateway aiChatGateway,
            AiProviderCatalog providerCatalog,
            TeachingAiDraftProperties aiDraftProperties) {
        this.aiChatGateway = aiChatGateway;
        this.providerCatalog = providerCatalog;
        this.aiDraftProperties = aiDraftProperties;
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
            StudentMemoryResponse memoryResponse,
            TeachingHandoutTemplateProfile template) {
        List<AiProviderCatalog.Provider> providers = selectedProviders(request);
        if (providers.isEmpty()) {
            return new TeachingTaskResponse.AiDraft(false, "", "", 0, 0, 0, "", "No enabled AI provider.");
        }
        RuntimeException lastFailure = null;
        TeachingTaskResponse.AiDraft lastUnstructuredDraft = null;
        List<TeachingTaskResponse.AiRecoveryEvent> recoveryEvents = new ArrayList<>();
        ModelImageContext imageContext = prepareModelImageContext(evidence);
        if (imageContext.available()) {
            recoveryEvents.add(event(
                    "IMAGE_CONTEXT_COMPRESSED",
                    providers.getFirst().name(),
                    providers.getFirst().chatModel(),
                    0,
                    false,
                    false,
                    imageContext.metricsJson()));
        } else if (!imageContext.failureCode().isBlank()) {
            recoveryEvents.add(event(
                    "IMAGE_CONTEXT_SKIPPED",
                    providers.getFirst().name(),
                    providers.getFirst().chatModel(),
                    0,
                    false,
                    false,
                    imageContext.failureCode()));
        }
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        int totalTokens = 0;
        int maxRetries = aiDraftProperties.resolvedMaxRetries();
        for (int providerIndex = 0; providerIndex < providers.size(); providerIndex++) {
            AiProviderCatalog.Provider provider = providers.get(providerIndex);
            String nextPrompt = prompt(request, evidence, memoryResponse, template);
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                boolean canRetryProvider = attempt < maxRetries;
                boolean canRotateProvider = providerIndex < providers.size() - 1;
                try {
                    AiChatResult result = aiChatGateway.call(new AiChatRequest(
                            provider.name(),
                            provider.chatModel(),
                            "CoursewareAgent",
                            nextPrompt,
                            evidenceRefs(evidence),
                            imageContext.dataUrl()));
                    totalPromptTokens += result.promptTokens();
                    totalCompletionTokens += result.completionTokens();
                    totalTokens += result.totalTokens();
                    recoveryEvents.add(event(
                            "MODEL_CALL_SUCCEEDED",
                            result.providerName(),
                            result.modelCode(),
                            attempt,
                            false,
                            true,
                            result.safeMessage()));
                    ParsedDraft parsed = parseStructuredDraft(result.generatedContent());
                    if (parsed.structured()) {
                        if (parsed.locallyRepaired()) {
                            recoveryEvents.add(event(
                                    "JSON_REPAIRED_LOCALLY",
                                    result.providerName(),
                                    result.modelCode(),
                                    attempt,
                                    true,
                                    false,
                                    "Escaped malformed LaTeX JSON locally without another model call."));
                        }
                        if (!appearsTopicAligned(request, parsed)) {
                            recoveryEvents.add(event(
                                    "TOPIC_ALIGNMENT_REJECTED",
                                    result.providerName(),
                                    result.modelCode(),
                                    attempt,
                                    false,
                                    canRetryProvider || canRotateProvider,
                                    "Structured draft drifted away from the requested topic."));
                            if (attempt == maxRetries) {
                                lastUnstructuredDraft = toAiDraft(
                                        result,
                                        parsed,
                                        totalPromptTokens,
                                        totalCompletionTokens,
                                        totalTokens,
                                        attempt,
                                        maxRetries,
                                        recoveryEvents);
                                break;
                            }
                            recoveryEvents.add(event(
                                    "RETRY_SCHEDULED",
                                    provider.name(),
                                    provider.chatModel(),
                                    attempt + 1,
                                    false,
                                    true,
                                    "Retrying after topic-alignment validation failure."));
                            nextPrompt = retryPrompt(request, evidence, memoryResponse, template, result.generatedContent(),
                                    "Structured output drifted away from the requested topic.");
                            continue;
                        }
                        if (!coversEveryRetrievedQuestion(evidence, parsed)) {
                            recoveryEvents.add(event(
                                    "QUESTION_COVERAGE_REJECTED",
                                    result.providerName(),
                                    result.modelCode(),
                                    attempt,
                                    false,
                                    canRetryProvider || canRotateProvider,
                                    "Teacher draft omitted one or more retrieved atomic question numbers."));
                            if (attempt == maxRetries) {
                                lastUnstructuredDraft = toAiDraft(
                                        result, parsed, totalPromptTokens, totalCompletionTokens, totalTokens,
                                        attempt, maxRetries, recoveryEvents);
                                break;
                            }
                            nextPrompt = retryPrompt(request, evidence, memoryResponse, template, result.generatedContent(),
                                    "The teacher explanation must contain every retrieved source question number as a separate item, with conditions, method, checkable steps, conclusion, and source note.");
                            continue;
                        }
                        recoveryEvents.add(event(
                                "JSON_PARSE_SUCCEEDED",
                                result.providerName(),
                                result.modelCode(),
                                attempt,
                                true,
                                false,
                                "Structured teaching draft parsed."));
                        return toAiDraft(
                                result,
                                parsed,
                                totalPromptTokens,
                                totalCompletionTokens,
                                totalTokens,
                                attempt,
                                maxRetries,
                                recoveryEvents);
                    }
                    recoveryEvents.add(event(
                            "JSON_PARSE_FAILED",
                            result.providerName(),
                            result.modelCode(),
                            attempt,
                            false,
                            canRetryProvider || canRotateProvider,
                            parsed.parseError()));
                    if (attempt == maxRetries) {
                        lastUnstructuredDraft = toAiDraft(
                                result,
                                parsed,
                                totalPromptTokens,
                                totalCompletionTokens,
                                totalTokens,
                                attempt,
                                maxRetries,
                                recoveryEvents);
                        break;
                    }
                    recoveryEvents.add(event(
                            "RETRY_SCHEDULED",
                            provider.name(),
                            provider.chatModel(),
                            attempt + 1,
                            false,
                            true,
                            "Retrying model output repair after JSON parse failure."));
                    nextPrompt = retryPrompt(request, evidence, memoryResponse, template, result.generatedContent(), parsed.parseError());
                } catch (RuntimeException exception) {
                    lastFailure = exception;
                    recoveryEvents.add(event(
                            "MODEL_CALL_FAILED",
                            provider.name(),
                            provider.chatModel(),
                            attempt,
                            false,
                            canRetryProvider || canRotateProvider,
                            exception.getClass().getSimpleName()));
                    if (attempt == maxRetries) {
                        break;
                    }
                    recoveryEvents.add(event(
                            "RETRY_SCHEDULED",
                            provider.name(),
                            provider.chatModel(),
                            attempt + 1,
                            false,
                            true,
                            "Retrying after transient model gateway failure."));
                    nextPrompt = transientFailureRetryPrompt(request, evidence, memoryResponse, template, exception);
                }
            }
            if (providerIndex < providers.size() - 1) {
                AiProviderCatalog.Provider nextProvider = providers.get(providerIndex + 1);
                recoveryEvents.add(event(
                        "PROVIDER_ROTATED",
                        nextProvider.name(),
                        nextProvider.chatModel(),
                        0,
                        false,
                        true,
                        "Switching to next enabled provider after failed attempts."));
            }
        }
        if (lastUnstructuredDraft != null) {
            return lastUnstructuredDraft;
        }
        return new TeachingTaskResponse.AiDraft(
                true,
                "",
                "",
                0,
                0,
                0,
                "",
                "AI provider failed: " + (lastFailure == null ? "unknown" : lastFailure.getClass().getSimpleName()),
                false,
                "",
                "",
                List.of(),
                List.of(),
                lastFailure == null ? "" : lastFailure.getClass().getSimpleName(),
                maxRetries,
                maxRetries,
                false,
                List.copyOf(recoveryEvents));
    }

    /**
     * Applies a teacher-selected route only when it is present in the backend allow-list. The default path retains
     * provider rotation; an explicit route is deliberately singular so the requested model cannot silently change.
     */
    private List<AiProviderCatalog.Provider> selectedProviders(TeachingTaskRequest request) {
        if (request != null && request.aiModelCode() != null && !request.aiModelCode().isBlank()) {
            AiProviderCatalog.Provider provider = providerCatalog
                    .preferredProvider(request.aiProviderName(), request.aiModelCode())
                    .orElseThrow(() -> new IllegalArgumentException("Selected AI provider/model is not enabled"));
            return List.of(provider);
        }
        return providerCatalog.enabledProviders();
    }

    /**
     * Backward-compatible overload used by older tests and internal call sites.
     */
    public TeachingTaskResponse.AiDraft draft(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse) {
        return draft(request, evidence, memoryResponse, DEFAULT_TEMPLATE);
    }

    private static TeachingTaskResponse.AiDraft toAiDraft(
            AiChatResult result,
            ParsedDraft parsed,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            int retryCount,
            int maxRetries,
            List<TeachingTaskResponse.AiRecoveryEvent> recoveryEvents) {
        String message = parsed.structured()
                ? result.safeMessage()
                : result.safeMessage() + " Structured parse failed after " + retryCount + " retry.";
        return new TeachingTaskResponse.AiDraft(
                true,
                result.providerName(),
                result.modelCode(),
                promptTokens,
                completionTokens,
                totalTokens,
                result.generatedContent(),
                message,
                parsed.structured(),
                parsed.teacherExplanation(),
                parsed.studentHint(),
                parsed.knowledgePoints(),
                parsed.followUpQuestions(),
                parsed.parseError(),
                retryCount,
                maxRetries,
                parsed.structured() && retryCount > 0,
                List.copyOf(recoveryEvents));
    }

    private static TeachingTaskResponse.AiRecoveryEvent event(
            String eventType,
            String providerName,
            String modelCode,
            int attemptNo,
            boolean structured,
            boolean retryable,
            String message) {
        return new TeachingTaskResponse.AiRecoveryEvent(
                eventType,
                providerName,
                modelCode,
                attemptNo,
                structured,
                retryable,
                safeEventMessage(message));
    }

    /**
     * Creates a bounded model-only copy of the first authorized evidence image. The original path remains on the
     * evidence object for lossless handout rendering and is deliberately absent from metrics and provider traces.
     */
    private static ModelImageContext prepareModelImageContext(List<TeachingEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return ModelImageContext.empty();
        }
        for (TeachingEvidence item : evidence) {
            if (item == null || item.imagePath() == null || item.imagePath().isBlank()) {
                continue;
            }
            try {
                Path source = Path.of(item.imagePath()).toAbsolutePath().normalize();
                if (!Files.isRegularFile(source)) {
                    return ModelImageContext.failed("authorized image is not a regular file");
                }
                long originalBytes = Files.size(source);
                if (originalBytes <= 0 || originalBytes > MODEL_IMAGE_MAX_SOURCE_BYTES) {
                    return ModelImageContext.failed("authorized image size is outside the model-context limit");
                }
                BufferedImage original = ImageIO.read(source.toFile());
                if (original == null || original.getWidth() <= 0 || original.getHeight() <= 0) {
                    return ModelImageContext.failed("authorized image format cannot be decoded");
                }
                BufferedImage compressed = resizeForModel(original);
                byte[] compressedBytes = encodePng(compressed);
                int originalTokens = estimateHighDetailTokens(original.getWidth(), original.getHeight());
                int compressedTokens = LOW_DETAIL_IMAGE_TOKENS;
                ImageContextMetrics metrics = new ImageContextMetrics(
                        original.getWidth(),
                        original.getHeight(),
                        compressed.getWidth(),
                        compressed.getHeight(),
                        originalBytes,
                        compressedBytes.length,
                        MODEL_IMAGE_DETAIL,
                        originalTokens,
                        compressedTokens,
                        Math.max(0, originalTokens - compressedTokens));
                return new ModelImageContext(
                        "data:image/png;base64," + Base64.getEncoder().encodeToString(compressedBytes),
                        OBJECT_MAPPER.writeValueAsString(metrics),
                        "");
            } catch (IOException | RuntimeException exception) {
                return ModelImageContext.failed(exception.getClass().getSimpleName());
            }
        }
        return ModelImageContext.empty();
    }

    /** Preserves the aspect ratio and uses bicubic interpolation so thin geometry lines remain readable. */
    private static BufferedImage resizeForModel(BufferedImage original) {
        int longEdge = Math.max(original.getWidth(), original.getHeight());
        if (longEdge <= MODEL_IMAGE_MAX_LONG_EDGE) {
            return original;
        }
        double scale = (double) MODEL_IMAGE_MAX_LONG_EDGE / longEdge;
        int width = Math.max(1, (int) Math.round(original.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(original.getHeight() * scale));
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(original, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return resized;
    }

    /** PNG is intentional: textbook line art and small formula glyphs must not acquire JPEG ringing. */
    private static byte[] encodePng(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IOException("PNG encoder is unavailable");
            }
            return output.toByteArray();
        }
    }

    /** Estimates the uncompressed high-detail budget using 512px tiles for an auditable before/after comparison. */
    private static int estimateHighDetailTokens(int width, int height) {
        int horizontalTiles = (width + HIGH_DETAIL_TILE_EDGE - 1) / HIGH_DETAIL_TILE_EDGE;
        int verticalTiles = (height + HIGH_DETAIL_TILE_EDGE - 1) / HIGH_DETAIL_TILE_EDGE;
        return HIGH_DETAIL_BASE_TOKENS + HIGH_DETAIL_TILE_TOKENS * horizontalTiles * verticalTiles;
    }

    private record ModelImageContext(String dataUrl, String metricsJson, String failureCode) {
        private static ModelImageContext empty() {
            return new ModelImageContext("", "", "");
        }

        private static ModelImageContext failed(String failureCode) {
            return new ModelImageContext("", "", failureCode == null ? "image compression failed" : failureCode);
        }

        private boolean available() {
            return !dataUrl.isBlank();
        }
    }

    private record ImageContextMetrics(
            int originalWidth,
            int originalHeight,
            int compressedWidth,
            int compressedHeight,
            long originalBytes,
            long compressedBytes,
            String detail,
            int estimatedTokensBefore,
            int estimatedTokensAfter,
            int estimatedTokensSaved) {
    }

    /**
     * Builds a classroom-ready prompt from real task data and retrieved evidence.
     */
    private static String prompt(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse,
            TeachingHandoutTemplateProfile template) {
        /*
         * A long list of negative layout rules caused real providers to spend most of their response budget
         * reconciling instructions instead of explaining the source questions.  Rendering and leakage guards already
         * run after generation, so the model receives a short content contract by default.  The legacy wording stays
         * opt-in only for forensic comparison of historic tasks.
         */
        if (!Boolean.getBoolean("math.agent.teaching.legacy-prompt")) {
            return compactPrintablePrompt(request, evidence, memoryResponse, template);
        }
        return """
                You are a high-school math lesson-preparation agent.
                Generate evidence-grounded content that can be placed directly into a polished printable handout.
                Return exactly one valid JSON object. Do not output Markdown, code fences, or extra explanation.
                All user-facing text values must be written in concise Chinese.
                Math must use Feishu-supported delimiters only: inline $...$ or display $$...$$.
                Do not use \\[...\\], \\(...\\), \\begin{align}, \\begin{aligned}, \\begin{equation}, or Markdown code fences.
                Do not output a complete LaTeX document. Never write \\documentclass, \\usepackage, \\begin{document}, \\end{document}, fancyhdr, titleformat, page style commands, or any preamble command in JSON values.
                Separate capabilities strictly: AI live explanation belongs to chat/dialogue features; this task generates printable handouts for teachers and students only.
                Treat template layout instructions as rendering constraints only. Do not write header/footer/color/PDF layout rules in any JSON value.
                JSON schema:
                {
                  "teacherExplanation": "Chinese teacher handout body. Required labels in this order: 【知识定位】【题型识别】【方法步骤】【例题详解】【答案与评分点】【易错提醒】【课堂追问】. Under 【例题详解】 and 【答案与评分点】, write one independently checkable entry per supplied source question number. Never copy OCR headers, page counters, publisher names, 【解析】/【分析】/【小问】 blocks, or a whole-paper scoring note. If a question has no reliable answer evidence, leave that question's answer entry empty rather than inventing or copying a global answer.",
                  "studentHint": "Student worksheet data only: concrete source-question prompts and intentional blanks. Do not write knowledge lectures, type-identification explanations, derivations, conclusions, answers, scoring points, or teacher notes. The renderer will add each real question and its source image; do not generate a replacement teacher handout.",
                  "knowledgePoints": ["3-6 Chinese knowledge points or method cards that are specific to the current topic/problem, with formulas, conditions, or method signals first; no generic study advice or placeholder text"],
                  "followUpQuestions": ["3-8 Chinese exercises/questions only, include easy/medium/hard progression when possible; no answers, no scoring points, no worked solutions"]
                }
                Do not write "as an AI". Do not invent sources not provided below.
                Do not output raw page OCR fragments, raw source ids, model names, token usage, backend diagnostics, JSON/parse/debug words, or model-health wording.
                Do not mention page header, page footer, colors, rendering engines, prompt rules, template rules, or "PDF layout requirements" as handout content.
                Before writing, silently reason through the topic, evidence alignment, conditions, and each algebraic/geometric transition. This internal reasoning is mandatory for quality control but must never be printed, summarized as a chain-of-thought, or mentioned as a prompt. Print only the concise conclusion and the mathematical reason that a teacher or student can verify.
                Teacher content must include only question-number-level answers when enough information is available from the problem/evidence; student content must leave blanks instead of answers. Never emit a global answer block.
                Teacher content is for human teacher review and printing. Student content is for classroom use and must not contain 【答案与评分点】, 【例题详解】, 参考答案, 评分标准, or complete solution paragraphs.
                Use question-bank difficulty and answer evidence only to organize teacher answers and student exercises; never expose raw JSON keys from question-bank metadata.
                When question-bank evidence exists, use the actual question text and answer evidence. For every concrete knowledge point represented by the retrieved evidence, attach at least one real atomic question as its worked example and, when a second row exists, one real variation. Never reuse a question from another point merely to fill a section. Group by 基础 / 提高 / 压轴 only when the evidence explicitly supports it.
                This is a long-form ten-question handout, not a six-example summary: enumerate every supplied QUESTION_BANK item in 【例题详解】. For each item write its visible source question number/title, a topic-specific method heading, 2-5 checkable deduction steps, and a final answer or a clearly derived conclusion. Do not replace an omitted item with general advice. If the prompt is long, compress prose but preserve every question's mathematical transition and conclusion. The answer block must never contain “第 N 页/共 M 页”, publisher branding, or truncated OCR prose.
                When the user gives only a topic and no verified question is retrieved, explain the concept from reliable evidence but explicitly leave the worked-example slot absent; never invent a question, source, answer, or citation merely to fill the page. Student exercises may be generated only when they are grounded in the supplied problem or retrieved evidence.
                Teacher content should mention compact source titles or page hints only when useful, for example [教材 p.152] or [题库-双曲线基础], but never paste long OCR paragraphs.
                Prefer standard LaTeX fractions such as $\\frac{k}{x}$, $\\frac{a+b}{c}$, and $\\frac{1}{2}$ instead of plain slash text like k/x, (a+b)/c, or 1/2 whenever the expression is mathematical.
                Student exercise wording should feel like a real printed worksheet: short prompts, continuous numbering, obvious writing space, and no long essay paragraphs. Student output is question-only; do not add “题型定位”“推导路径”“结论核对” or any explanation beside a question.
                Leave visible writing space in student content with clean blank lines or ___ only; do not write visible labels such as 作答区、手写区、留白区、推导区、板书区 before blank space; avoid scattering oversized blank areas after every small point.
                Keep each labeled block compact: prefer formulas, numbered steps, short bullets, and explicit blanks over long prose.
                The learning goal and problem are the highest-priority topic constraint. Before writing, extract the core topic from them and keep every section on that exact topic.
                If any retrieved evidence is off-topic, noisy, OCR-broken, or belongs to another chapter, ignore it instead of switching topics. Never let unrelated evidence override the learning goal.
                When reliable evidence is missing, stay with the requested topic and explain only verifiable mathematical facts; state the evidence gap in backend metadata, not in printable prose, and never fabricate an example or source.
                Do not output placeholder structure text such as “知识点1/2/3”, “题型1/2/3”, “例题待补充”, “题目内容待补充”, “先把主题拆成定义、公式”, “本讲更偏向”, or any sentence that only explains how to write the handout instead of teaching math.
                Section headings are content-owned: choose a short, topic-specific heading for each method/strategy (for example, “从法向量到线面角” for a line-plane-angle lesson). Do not force generic headings such as “核心方法”“方法主线”“解题步骤”; those words are instructions, not printable content.
                If a custom strategy heading is useful, place one line `方法标题：<具体数学策略>` inside 【方法步骤】; omit that line when no concise heading is warranted.
                Output only actual teaching content or intentional blank workspace. Do not write meta-operational sentences such as “本页只保留…”, “课堂任务…”, “本讲任务…”, “讲后自查…”, “教师审校清单…”, or “横版讲解提纲…”.
                Respect printable handout layout, but do not describe layout rules such as header/footer or color requirements as user-facing content.
                If the user only gives a topic rather than a problem, organize the verified knowledge points and omit any ungrounded example instead of fabricating one.
                Selected handout template: %s
                Template context: %s
                Template content instructions: %s
                Learning goal: %s
                Problem: %s
                Supplementary requirements (never print as a question): %s
                Reused memory: %s
                Retrieved evidence: %s
                """.formatted(
                template.summary().displayName(),
                templateContext(template),
                safeTemplatePromptText(template.promptInstructions()),
                safeTaskText(request.learningGoal()),
                safeTaskText(request.questionText()),
                safeTaskText(request.supplementaryRequirements()),
                memoryResponse.reused() ? memoryResponse.answer() : memoryResponse.reason(),
                evidence.stream().map(TeachingAiDraftService::evidenceLine).toList());
    }

    /**
     * Supplies one clear content contract to the real model; the Java renderer owns all typography and page layout.
     * This separation prevents internal PDF rules from being copied into teacher or student handouts.
     */
    private static String compactPrintablePrompt(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse,
            TeachingHandoutTemplateProfile template) {
        return """
                你是一名高中数学教研老师。请依据下列真实题库资料生成可直接印刷的讲义内容。
                只输出一个合法 JSON 对象，不要输出代码块、解释或任何额外文字。所有内容使用简洁中文，公式用 $...$。

                JSON 结构必须为：
                {
                  "teacherExplanation":"教师版。依次包含【知识定位】【题型识别】【方法步骤】【例题详解】【答案与评分点】【易错提醒】【课堂追问】；答案必须按真实题号逐题给出，禁止整卷 OCR 解析、页码、出版社或截断原文。",
                  "studentHint":"学生版只提供真实题目和作答留白，不提供知识讲解、题型定位、推导路径、结论、答案、评分点或教师提示。",
                  "knowledgePoints":["具体知识点或方法卡，3至6条"],
                  "followUpQuestions":["无答案的学生练习，3至8题"]
                }

                教师版只写可核验的数学结论、条件和推导。若有多道 QUESTION_BANK 题，在【例题详解】和【答案与评分点】中按原题号逐题列出；每题给出方法名、2至5个关键步骤和结论，不能遗漏、合并、杜撰或改写题目。禁止复制“答案要点：答案：第 N 页/共 M 页”、学科网、出版社、【解析】、【分析】、【小问】等 OCR 块；题号级答案缺失时留空，不能以全卷说明替代。
                学生版只保留真实题目、同题原图和作答空白，不出现知识讲解、题型定位、推导路径、结论、答案、评分点或教师提示。不要写任何系统、模型、资料路径、模板、排版或调试内容。
                讲义主题：%s
                用户任务：%s
                补充要求（只用于编排，不得写成题目）：%s
                模板风格：%s
                已复用学习记录：%s
                真实资料：%s
                """.formatted(
                safeTaskText(request.learningGoal()),
                safeTaskText(request.questionText()),
                safeTaskText(request.supplementaryRequirements()),
                safeTemplatePromptText(template.summary().displayName()),
                memoryResponse.reused() ? memoryResponse.answer() : memoryResponse.reason(),
                evidence.stream().map(TeachingAiDraftService::evidenceLine).toList());
    }

    private static String retryPrompt(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse,
            TeachingHandoutTemplateProfile template,
            String previousContent,
            String parseError) {
        return """
                The previous output failed backend JSON schema parsing.
                Fix format only. Do not add sources. Do not output Markdown.
                All user-facing text values must be written in concise Chinese.
                Math must use only $...$ or $$...$$; never use \\[...\\], \\(...\\), or align/equation environments.
                Do not output a complete LaTeX document. Never write \\documentclass, \\usepackage, \\begin{document}, \\end{document}, fancyhdr, titleformat, page style commands, or any preamble command in user-facing values.
                Do not write header/footer/color/PDF layout rules, AI, token, debug, JSON, or model-health wording inside user-facing values.
                AI live explanation belongs to chat/dialogue features; this retry still generates printable handouts only.
                Parse error: %s
                Previous output: %s

                Return exactly one valid JSON object with all fields present and non-empty:
                {
                  "teacherExplanation": "printable Chinese teacher handout body with labels 【知识定位】【题型识别】【方法步骤】【例题详解】【答案与评分点】【易错提醒】【课堂追问】; answers are question-number-specific only, never OCR page headers, publisher text, or whole-paper scoring prose",
                  "studentHint": "student question prompts and blanks only; no lecture, type定位, deduction path, conclusion, answer, scoring point, or teacher note",
                  "knowledgePoints": ["..."],
                  "followUpQuestions": ["student-safe questions only; no answer/scoring/solution leakage"]
                }
                Student content must not contain 【答案与评分点】, 【例题详解】, 参考答案, 评分标准, or complete solution paragraphs. It must not contain “题型定位”“推导路径”“结论核对”.
                Silently verify the topic, evidence-to-point mapping, conditions, and every algebraic/geometric transition before writing. Do not reveal chain-of-thought or this instruction; print only the verifiable result and its mathematical reason.
                Keep student exercises continuously numbered with visible blank space, but do not label blank space as 作答区、手写区、留白区、推导区、板书区; keep mathematical fractions in standard LaTeX \\frac form instead of slash text.
                The learning goal and problem remain the highest-priority topic constraint. If retrieved evidence looks off-topic or broken, ignore it and stay on the requested topic.
                Do not output placeholder structure text such as “知识点1/2/3”, “题型1/2/3”, or generic process advice that is detached from the current math topic.
                Use a short topic-specific heading for each strategy when a heading is needed; do not force “核心方法”“方法主线”“解题步骤”.
                If useful, place one line `方法标题：<具体数学策略>` inside 【方法步骤】; never use a generic or system heading.
                Output only actual teaching content or intentional blank workspace; remove any meta-operational sentence such as “本页只保留…”, “课堂任务…”, “本讲任务…”, or “教师审校清单…”.
                If question-bank evidence exists, prefer real题型/题目组织，并按基础 / 提高 / 压轴递进； if the user only gives a topic without evidence, do not fabricate printable exercises.
                Selected handout template: %s
                Template context: %s
                Template content instructions: %s
                Learning goal: %s
                Problem: %s
                Supplementary requirements (never print as a question): %s
                Reused memory: %s
                Retrieved evidence: %s
                """.formatted(
                parseError,
                previousContent == null ? "" : previousContent,
                template.summary().displayName(),
                templateContext(template),
                safeTemplatePromptText(template.promptInstructions()),
                safeTaskText(request.learningGoal()),
                safeTaskText(request.questionText()),
                safeTaskText(request.supplementaryRequirements()),
                memoryResponse.reused() ? memoryResponse.answer() : memoryResponse.reason(),
                evidence.stream().map(TeachingAiDraftService::evidenceLine).toList());
    }

    /**
     * Injects style and source metadata that helps dynamic template skills affect generation without exposing local paths.
     */
    private static String templateContext(TeachingHandoutTemplateProfile template) {
        var summary = template.summary();
        List<String> parts = new ArrayList<>();
        addTemplatePart(parts, "code", summary.templateCode());
        addTemplatePart(parts, "source", summary.sourceType());
        addTemplatePart(parts, "audience", summary.audience());
        addTemplatePart(parts, "category", summary.category());
        addTemplatePart(parts, "visualStyle", summary.visualStyle());
        addTemplatePart(parts, "studentBlankSpaceEm", String.valueOf(template.blankSpaceEm()));
        addTemplatePart(parts, "questionGapEm", String.valueOf(template.questionGapEm()));
        if (summary.difficultyBands() != null && !summary.difficultyBands().isEmpty()) {
            addTemplatePart(parts, "difficulty", String.join("/", summary.difficultyBands()));
        }
        if (summary.tags() != null && !summary.tags().isEmpty()) {
            addTemplatePart(parts, "tags", String.join("/", summary.tags()));
        }
        addTemplatePart(parts, "referenceTitle", summary.referenceTitle());
        addTemplatePart(parts, "referenceSummary", safeTemplatePromptText(summary.referencePreview()));
        return String.join("; ", parts);
    }

    private static void addTemplatePart(List<String> parts, String key, String value) {
        String safe = safeTemplatePromptText(value);
        if (!safe.isBlank()) {
            parts.add(key + "=" + safe);
        }
    }

    /**
     * Template skills may mention rendering/layout constraints for humans configuring the skill.
     * The model only needs content-side constraints; stripping these words reduces header/footer/color leakage.
     */
    private static String safeTemplatePromptText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value
                .replaceAll("(?i)[A-Z]:\\\\[^\\s，。；;]+", " ")
                .replaceAll("(?i)/(?:Users|home|var|tmp|mnt)/[^\\s，。；;]+", " ")
                .replaceAll("(?i)file://\\S+", " ");
        normalized = normalized
                .replaceAll("(?i)MODEL_CALL|JSON_PARSE|\\btokens?\\b|模型健康|model health|debug|调试|JSON", " ")
                .replaceAll("(?i)documentclass|usepackage|fancyhdr|pagestyle|begin\\{document}|end\\{document}", " ")
                .replace("页眉", "")
                .replace("页脚", "")
                .replace("颜色", "")
                .replace("渲染引擎", "")
                .replace("渲染规则", "")
                .replace("模板规则", "")
                .replace("页面颜色", "")
                .replace("讲评色", "")
                .replace("练习色", "")
                .replace("PDF 规则", "")
                .replace("PDF规则", "")
                .replace("PDF 版式", "")
                .replace("PDF版式", "")
                .replace("PDF 排版", "")
                .replace("PDF排版", "")
                .replaceAll("\\s+", " ")
                .strip();
        if (normalized.length() > 900) {
            return normalized.substring(0, 900).strip();
        }
        return normalized;
    }

    private static String transientFailureRetryPrompt(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse,
            TeachingHandoutTemplateProfile template,
            RuntimeException exception) {
        return prompt(request, evidence, memoryResponse, template)
                + "\nPrevious provider call failed with: " + exception.getClass().getSimpleName()
                + ". This is an automatic backend retry. Still return only valid JSON.";
    }

    private static boolean appearsTopicAligned(TeachingTaskRequest request, ParsedDraft parsed) {
        List<String> anchors = topicAnchors(request);
        if (anchors.isEmpty()) {
            return true;
        }
        if (anchors.stream().noneMatch(anchor -> anchor.length() >= 3)) {
            return true;
        }
        String haystack = ((parsed.teacherExplanation() == null ? "" : parsed.teacherExplanation()) + " "
                + (parsed.studentHint() == null ? "" : parsed.studentHint()) + " "
                + String.join(" ", parsed.knowledgePoints()) + " "
                + String.join(" ", parsed.followUpQuestions()))
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
        int matched = 0;
        int weighted = 0;
        for (String anchor : anchors) {
            String normalized = anchor.toLowerCase(Locale.ROOT);
            if (haystack.contains(normalized)) {
                matched += 1;
                weighted += anchor.length();
            }
        }
        return matched >= 1 || weighted >= 2;
    }

    /**
     * Rejects a polished-looking summary that silently drops real question-bank rows.
     *
     * <p>The requirement is intentionally tied to atomic source numbers rather than word count: a generic paragraph
     * can be long yet still fail to explain any individual exam question.  Only enforce it when two or more numbered
     * question-bank items are present, so a normal single-question chat-like handout remains valid.</p>
     */
    private static boolean coversEveryRetrievedQuestion(List<TeachingEvidence> evidence, ParsedDraft parsed) {
        Set<String> expected = new LinkedHashSet<>();
        for (TeachingEvidence item : evidence == null ? List.<TeachingEvidence>of() : evidence) {
            if (!"QUESTION_BANK".equals(item.sourceScope())) {
                continue;
            }
            Matcher matcher = SOURCE_QUESTION_NUMBER.matcher(questionTextOnly(item.snippet()));
            if (matcher.find()) {
                expected.add(matcher.group(1));
            }
        }
        if (expected.size() < 2) {
            return true;
        }
        String teacher = parsed.teacherExplanation() == null ? "" : parsed.teacherExplanation();
        for (String number : expected) {
            if (!Pattern.compile("(?m)(?:第\\s*" + Pattern.quote(number) + "\\s*题|^\\s*" + Pattern.quote(number) + "[.．、])")
                    .matcher(teacher).find()) {
                return false;
            }
        }
        return true;
    }

    private static List<String> topicAnchors(TeachingTaskRequest request) {
        String raw = (safeTaskText(request.learningGoal()) + " "
                + safeTaskText(request.questionText()))
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]+", " ")
                .replaceAll("(?:请|生成|一份|关于|围绕|针对|包含|以及|并|和|与|从|到|开始|讲解|讲义|学习|学会|理解|掌握|做题|大题|小题|题型|例题|专题|训练|教师版|学生版|教师|学生|课堂|作答|补充要求|要求|目标|主题|知识点|基础|提高|综合|题目|问题|复习|巩固|提升|中的|中|的)", " ");
        List<String> anchors = new ArrayList<>();
        for (String preferred : PREFERRED_TOPIC_ANCHORS) {
            if (raw.contains(preferred)) {
                anchors.add(preferred);
            }
        }
        for (String candidate : QuestionBankSearchText.candidateQueries(
                safeTaskText(request.learningGoal()), safeTaskText(request.questionText()))) {
            if (candidate.length() >= 2 && candidate.length() <= 12 && !anchors.contains(candidate)) {
                anchors.add(candidate);
            }
        }
        for (String part : raw.split("\\s+")) {
            String candidate = part.strip();
            if (candidate.length() >= 2
                    && candidate.length() <= 12
                    && !"数学".equals(candidate)
                    && !"高中数学".equals(candidate)
                    && !anchors.contains(candidate)) {
                anchors.add(candidate);
            }
        }
        anchors.sort((left, right) -> Integer.compare(right.length(), left.length()));
        return anchors.size() > 6 ? anchors.subList(0, 6) : anchors;
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
        if ("QUESTION_BANK".equals(evidence.sourceScope())) {
            return questionBankEvidenceLine(evidence);
        }
        String line = evidence.sourceScope()
                + "/"
                + safeEvidenceTitle(evidence.sourceTitle())
                + "/p."
                + evidence.pageNo()
                + ": "
                + removeOpaqueIdentifiers(TeachingEvidenceSnippetSanitizer.sanitizeCompact(evidence.snippet()));
        String imageDescription = safeImageDescription(evidence.imageDescription());
        if (!imageDescription.isBlank()) {
            // This is evidence from a permission-checked asset reader, not an instruction to infer hidden edges,
            // colors, or an answer. The explicit boundary keeps a model from turning a sparse OCR/vision caption
            // into fabricated graph adjacency or a made-up solution.
            line += "\n图像已核验可见信息（仅限所列文字、公式、标号；不可补造图形关系或答案）：" + imageDescription;
        }
        return line;
    }

    /** Removes transport locations before a real visual caption reaches the model prompt. */
    private static String safeImageDescription(String value) {
        String withoutLocations = value == null ? "" : value
                .replaceAll("(?i)https?://[^\\s，。；;]+", " ")
                .replaceAll("(?i)[A-Z]:[\\\\/][^\\s，。；;]+", " ");
        String cleaned = removeOpaqueIdentifiers(TeachingEvidenceSnippetSanitizer.sanitizeCompact(withoutLocations));
        return TeachingEvidenceSnippetSanitizer.LOW_QUALITY_SNIPPET.equals(cleaned) ? "" : cleaned;
    }

    /** Keeps readable source titles while hiding opaque asset/document identifiers from printable content. */
    static String safeEvidenceTitle(String value) {
        if (value == null || value.isBlank()) {
            return "未命名资料";
        }
        String cleaned = value
                .replaceAll("\\b[A-Za-z0-9]{24,}\\b", "")
                .replaceAll("\\s{2,}", " ")
                .strip();
        // Removing an opaque document id can leave its visual separator behind (for example "讲义 / ").
        // Trim only trailing separators so legitimate slashes inside a source title remain readable.
        cleaned = cleaned.replaceAll("[\\s/\\\\|]+$", "").strip();
        return cleaned.isBlank() ? "未命名资料" : cleaned;
    }

    private static String questionBankEvidenceLine(TeachingEvidence evidence) {
        String answer = questionAnswerOnly(evidence.snippet());
        String line = "QUESTION_BANK/"
                + safeEvidenceTitle(evidence.sourceTitle())
                + "/difficulty:"
                + questionDifficulty(evidence)
                + ": 题目："
                + removeOpaqueIdentifiers(TeachingEvidenceSnippetSanitizer.sanitizeCompact(questionTextOnly(evidence.snippet())));
        if (!answer.isBlank()) {
            line += "；答案要点：" + removeOpaqueIdentifiers(TeachingEvidenceSnippetSanitizer.sanitizeCompact(answer));
        }
        return line;
    }

    private static String questionDifficulty(TeachingEvidence evidence) {
        String title = evidence.sourceTitle() == null ? "" : evidence.sourceTitle();
        int index = title.indexOf("难度：");
        if (index >= 0) {
            return title.substring(index + "难度：".length()).strip();
        }
        index = title.indexOf("难度:");
        if (index >= 0) {
            return title.substring(index + "难度:".length()).strip();
        }
        return "未标难度";
    }

    private static String questionTextOnly(String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return "题目内容待补充";
        }
        return splitAnswerMarker(snippet)[0].replaceAll("\\s+", " ").strip();
    }

    private static String questionAnswerOnly(String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return "";
        }
        String[] parts = splitAnswerMarker(snippet);
        if (parts.length < 2 || parts[1].isBlank()) {
            return "";
        }
        return QuestionBankAnswerFormatter.format(parts[1].strip());
    }

    private static String[] splitAnswerMarker(String snippet) {
        String normalized = snippet.replace("\r", "\n");
        String[] chineseParts = normalized.split("答案要点：", 2);
        if (chineseParts.length == 2) {
            return chineseParts;
        }
        String[] asciiParts = normalized.split("答案要点:", 2);
        if (asciiParts.length == 2) {
            return asciiParts;
        }
        return new String[] {normalized};
    }

    /**
     * Parses model content into the expected classroom JSON schema without inventing missing fields.
     */
    static ParsedDraft parseStructuredDraft(String content) {
        if (content == null || content.isBlank()) {
            return ParsedDraft.failed("empty model content");
        }
        String extractedJson = extractJsonObject(stripCodeFence(content.strip()));
        String json = normalizeTeXEscapesInJson(extractedJson);
        try {
            StructuredDraftJson parsed = OBJECT_MAPPER.readValue(json, StructuredDraftJson.class);
            String teacherExplanation = normalizeText(parsed.teacherExplanation());
            String studentHint = normalizeStudentWorksheetText(normalizeText(parsed.studentHint()));
            List<String> knowledgePoints = normalizeList(parsed.knowledgePoints());
            List<String> followUpQuestions = normalizeStudentExerciseList(parsed.followUpQuestions());
            if (containsContentPlaceholder(teacherExplanation)
                    || containsContentPlaceholder(studentHint)
                    || knowledgePoints.stream().anyMatch(TeachingAiDraftService::containsContentPlaceholder)
                    || followUpQuestions.stream().anyMatch(TeachingAiDraftService::containsContentPlaceholder)) {
                return ParsedDraft.failed("content contains an unresolved placeholder");
            }
            if (teacherExplanation.isBlank()
                    || studentHint.isBlank()
                    || knowledgePoints.isEmpty()
                    || followUpQuestions.isEmpty()) {
                return ParsedDraft.failed("JSON schema missing required nonblank teaching fields");
            }
            return new ParsedDraft(
                    true,
                    teacherExplanation,
                    studentHint,
                    knowledgePoints,
                    followUpQuestions,
                    "",
                    !json.equals(extractedJson));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return ParsedDraft.failed(exception.getClass().getSimpleName() + ": " + safeErrorMessage(exception));
        }
    }

    /** Rejects unresolved template markers after sanitization so a malformed draft cannot reach PDF rendering. */
    private static boolean containsContentPlaceholder(String value) {
        return value != null && !value.isBlank() && CONTENT_PLACEHOLDER.matcher(value).find();
    }

    private static String stripCodeFence(String content) {
        if (!content.startsWith("```")) {
            return content;
        }
        int firstLineEnd = content.indexOf('\n');
        int lastFenceStart = content.lastIndexOf("```");
        if (firstLineEnd >= 0 && lastFenceStart > firstLineEnd) {
            return content.substring(firstLineEnd + 1, lastFenceStart).strip();
        }
        return content;
    }

    private static String extractJsonObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return content;
        }
        return content.substring(start, end + 1);
    }

    /**
     * Repairs a common OpenAI-compatible response boundary: models correctly write TeX commands and delimiters
     * inside JSON strings, but omit JSON's second escape slash. The scanner consumes escape pairs atomically, so an
     * escaped quote cannot accidentally change string state. Valid JSON escapes are preserved; ambiguous control
     * escapes such as {@code \beta} are treated as TeX when another command letter follows. This deterministic local
     * pass avoids a paid model retry for a transport-format defect.
     */
    private static String normalizeTeXEscapesInJson(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder(json.length() + 64);
        boolean inString = false;
        for (int index = 0; index < json.length(); index += 1) {
            char current = json.charAt(index);
            if (current == '"') {
                inString = !inString;
                result.append(current);
                continue;
            }
            if (!inString) {
                result.append(current);
                continue;
            }
            if (current == '\n') {
                result.append("\\n");
                continue;
            }
            if (current == '\r') {
                result.append("\\r");
                continue;
            }
            if (current == '\t') {
                result.append("\\t");
                continue;
            }
            if (current != '\\' || index + 1 >= json.length()) {
                result.append(current);
                continue;
            }
            char next = json.charAt(index + 1);
            char afterNext = index + 2 < json.length() ? json.charAt(index + 2) : '\0';
            boolean validUnicodeEscape = next == 'u' && hasFourHexDigits(json, index + 2);
            boolean validSimpleEscape = next == '"' || next == '\\' || next == '/'
                    || ((next == 'b' || next == 'f' || next == 'n' || next == 'r' || next == 't')
                    && !isAsciiLowercase(afterNext));
            if (!validUnicodeEscape && !validSimpleEscape) {
                // Prepend one slash; the original slash and command character are appended as one consumed pair.
                result.append('\\');
            }
            result.append(current).append(next);
            index += 1;
        }
        return result.toString();
    }

    /** TeX command continuations are lowercase; normal JSON newlines commonly precede Chinese or capitalized text. */
    private static boolean isAsciiLowercase(char value) {
        return value >= 'a' && value <= 'z';
    }

    /** Distinguishes a four-hex-digit JSON unicode escape from a TeX command beginning with a slash and u. */
    private static boolean hasFourHexDigits(String value, int start) {
        if (start + 4 > value.length()) {
            return false;
        }
        for (int index = start; index < start + 4; index += 1) {
            if (Character.digit(value.charAt(index), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeText(String value) {
        String normalized = removeOpaqueIdentifiers(FormulaMarkupSanitizer.sanitizeFeishuMath(value));
        return removeInternalHandoutLines(normalized);
    }

    /** Removes opaque asset/document ids that are useful for audit metadata but not printable lesson content. */
    private static String removeOpaqueIdentifiers(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("\\b[A-Za-z0-9]{24,}\\b", "");
    }

    /**
     * Removes workflow/test directives from the model's topic context while
     * retaining real mathematical wording. These directives are control data,
     * not a problem statement, and must never become printable lesson text.
     */
    private static String safeTaskText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String sanitized = TASK_CONTROL_LINE.matcher(value.replace("\r", "\n")).replaceAll("");
        return sanitized.replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static String removeInternalHandoutLines(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return INTERNAL_HANDOUT_LINE.matcher(value)
                .replaceAll("")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private static String normalizeStudentWorksheetText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String sanitized = STUDENT_FORBIDDEN_SECTION.matcher(value).replaceAll("");
        sanitized = STUDENT_FORBIDDEN_LINE.matcher(sanitized).replaceAll("");
        sanitized = removeVisibleWorkspaceLabels(sanitized);
        sanitized = sanitized.replaceAll("\\n{3,}", "\n\n").strip();
        if (sanitized.isBlank()) {
            return """
                    【知识速记】先写出本题对应的定义、公式或图像特征。
                    【例题任务】独立完成关键步骤，写清计算过程。
                    【作答提醒】先写关键依据，再整理计算或证明步骤。
                    """.strip();
        }
        return sanitized;
    }

    private static String removeVisibleWorkspaceLabels(String value) {
        String withoutReferences = VISIBLE_WORKSPACE_REFERENCE.matcher(value).replaceAll("独立完成");
        return VISIBLE_WORKSPACE_LABEL.matcher(withoutReferences).replaceAll("");
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            String item = normalizeText(value);
            if (!item.isBlank()) {
                normalized.add(item);
            }
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizeStudentExerciseList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            String item = studentSafeExerciseText(normalizeText(value));
            if (!item.isBlank()) {
                normalized.add(item);
            }
        }
        return List.copyOf(normalized);
    }

    private static String studentSafeExerciseText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String sanitized = STUDENT_FORBIDDEN_SECTION.matcher(value).replaceAll("");
        sanitized = sanitized
                .replaceAll("(?i)(参考答案|答案|评分点|评分标准|完整解析|解答如下|解：|因此答案为|故答案为)[：:].*$", "")
                .replaceAll("(?i)(参考答案|答案|评分点|评分标准|完整解析|解答如下|解：|因此答案为|故答案为).*$", "")
                .replaceAll("\\s+", " ")
                .strip();
        sanitized = removeVisibleWorkspaceLabels(sanitized);
        return sanitized;
    }

    private static String safeErrorMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "";
        }
        return message.length() <= 180 ? message : message.substring(0, 180);
    }

    private static String safeEventMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String stripped = message.strip();
        // Compression metrics and provider diagnostics are persisted as audit JSON, so keep the complete bounded text.
        int maxAuditMessageLength = 2_048;
        return stripped.length() <= maxAuditMessageLength ? stripped : stripped.substring(0, maxAuditMessageLength);
    }

    record ParsedDraft(
            boolean structured,
            String teacherExplanation,
            String studentHint,
            List<String> knowledgePoints,
            List<String> followUpQuestions,
            String parseError,
            boolean locallyRepaired) {

        static ParsedDraft failed(String parseError) {
            return new ParsedDraft(false, "", "", List.of(), List.of(), parseError, false);
        }
    }

    private record StructuredDraftJson(
            String teacherExplanation,
            String studentHint,
            List<String> knowledgePoints,
            List<String> followUpQuestions) {
    }
}
