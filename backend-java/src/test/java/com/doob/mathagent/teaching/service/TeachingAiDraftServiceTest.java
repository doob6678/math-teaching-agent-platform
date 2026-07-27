package com.doob.mathagent.teaching.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.service.AiChatGateway;
import com.doob.mathagent.agent.service.AiChatRequest;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.memory.vo.StudentMemoryResponse;
import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.vo.TeachingHandoutTemplateResponse;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.util.ArrayList;
import java.util.List;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeachingAiDraftServiceTest {

    /** Luna commonly emits mathematically correct TeX with JSON-invalid single slashes; this must cost one call. */
    @Test
    void repairsLatexCommandsAndDelimitersLocallyWithoutPaidRetry() {
        String malformedLunaJson = """
                {
                  "teacherExplanation": "New function definition: $\\delta>0$, $A=\\{x\\,|\\,x>0\\}$ and \\\"quoted condition\\\".",
                  "studentHint": "Substitute into $D\\left(x\\right)$, then check the condition.",
                  "knowledgePoints": ["new function definition", "$\\beta$ classification"],
                  "followUpQuestions": ["How to find $D\\left(0\\right)$?"]
                }
                """;
        CapturingGateway gateway = new CapturingGateway(List.of(new AiChatResult(
                "openai", "gpt-5.6-luna", 30, 20, 50, "ok", malformedLunaJson)));
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, false), defaultPolicy());

        TeachingTaskResponse.AiDraft draft = service.draft(request(), evidence(), memory());

        assertThat(gateway.requests()).hasSize(1);
        assertThat(draft.structured()).isTrue();
        assertThat(draft.teacherExplanation()).contains("\\delta", "\\{", "\\,", "quoted condition");
        assertThat(draft.studentHint()).contains("\\left", "\\right");
        assertThat(draft.recoveryEvents()).extracting(TeachingTaskResponse.AiRecoveryEvent::eventType)
                .containsExactly("MODEL_CALL_SUCCEEDED", "JSON_REPAIRED_LOCALLY", "JSON_PARSE_SUCCEEDED");
        assertThat(draft.retryCount()).isZero();
        assertThat(draft.totalTokens()).isEqualTo(50);
    }

    @Test
    void compressesAuthorizedEvidenceImageAndRecordsEstimatedTokenSavings(@TempDir Path tempDir) throws Exception {
        Path imagePath = tempDir.resolve("large-diagram.png");
        BufferedImage image = new BufferedImage(2400, 1600, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        ImageIO.write(image, "png", imagePath.toFile());
        long originalBytes = Files.size(imagePath);
        CapturingGateway gateway = new CapturingGateway(List.of(new AiChatResult(
                "openai", "gpt-5.4", 20, 4, 24, "ok", structuredJson("compressed image draft"))));
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, false), defaultPolicy());
        List<TeachingEvidence> imageEvidence = List.of(new TeachingEvidence(
                "TEACHER_RESOURCE", "解三角形图题", "block-image", 1,
                "如图，在三角形ABC中求边长。", imagePath.toString(), ""));

        TeachingTaskResponse.AiDraft draft = service.draft(request(), imageEvidence, memory());

        assertThat(gateway.requests()).hasSize(1);
        assertThat(gateway.requests().getFirst().imageDataUrl()).startsWith("data:image/png;base64,");
        assertThat(gateway.requests().getFirst().imageDataUrl().length()).isLessThan((int) originalBytes * 2);
        assertThat(draft.recoveryEvents()).anySatisfy(event -> {
            assertThat(event.eventType()).isEqualTo("IMAGE_CONTEXT_COMPRESSED");
            assertThat(event.message()).contains(
                    "\"originalWidth\":2400",
                    "\"compressedWidth\":1536",
                    "\"detail\":\"low\"",
                    "\"estimatedTokensSaved\":");
        });
    }

    @Test
    void removesTrailingSeparatorsLeftByOpaqueEvidenceIdentifiers() {
        assertThat(TeachingAiDraftService.safeEvidenceTitle("教师讲义 / AnZ3d5Qbfo9K8IxK7AecZa3unBg"))
                .isEqualTo("教师讲义");
        assertThat(TeachingAiDraftService.safeEvidenceTitle("题型方法 /"))
                .isEqualTo("题型方法");
        assertThat(TeachingAiDraftService.safeEvidenceTitle("A/B 章节"))
                .isEqualTo("A/B 章节");
    }

    @Test
    void parsesStructuredJsonFromCodeFence() {
        TeachingAiDraftService.ParsedDraft parsed = TeachingAiDraftService.parseStructuredDraft("""
                ```json
                {
                  "teacherExplanation": "Explain \\\\(D(x_0)\\\\), then substitute x_0=-1.",
                  "studentHint": "Use \\\\[f(x)=x^2-4x+3\\\\] first, then check the condition.",
                  "knowledgePoints": ["new function definition", "\\\\begin{align} f(x)&=x^2-4x+3 \\\\\\\\ &= (x-1)(x-3) \\\\end{align}"],
                  "followUpQuestions": ["How to find D(0)?", "What changes when parameters move?"]
                }
                ```
                """);

        assertThat(parsed.structured()).isTrue();
        assertThat(parsed.teacherExplanation()).contains("$D(x_0)$").doesNotContain("\\(");
        assertThat(parsed.studentHint()).contains("$$").doesNotContain("\\[");
        assertThat(parsed.knowledgePoints().get(1)).contains("$$", "f(x)=x^2-4x+3").doesNotContain("\\begin{align}");
        assertThat(parsed.parseError()).isBlank();
    }

    @Test
    void retriesMalformedJsonAndReturnsRecoveredStructuredDraftWithAccumulatedTokens() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("openai", "gpt-5.4", 10, 4, 14, "ok", "teacher explanation: not JSON"),
                new AiChatResult("openai", "gpt-5.4", 12, 8, 20, "ok", structuredJson("repaired teacher explanation"))));
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, false), defaultPolicy());

        TeachingTaskResponse.AiDraft draft = service.draft(request(), evidence(), memory());

        assertThat(draft.structured()).isTrue();
        assertThat(draft.recoveredAfterRetry()).isTrue();
        assertThat(draft.retryCount()).isEqualTo(1);
        assertThat(draft.maxRetries()).isEqualTo(1);
        assertThat(draft.totalTokens()).isEqualTo(34);
        assertThat(draft.teacherExplanation()).contains("repaired teacher explanation");
        assertThat(draft.recoveryEvents()).extracting(TeachingTaskResponse.AiRecoveryEvent::eventType)
                .containsExactly(
                        "MODEL_CALL_SUCCEEDED",
                        "JSON_PARSE_FAILED",
                        "RETRY_SCHEDULED",
                        "MODEL_CALL_SUCCEEDED",
                        "JSON_PARSE_SUCCEEDED");
        assertThat(draft.recoveryEvents().get(1).retryable()).isTrue();
        assertThat(gateway.requests()).hasSize(2);
        assertThat(gateway.requests().getFirst().userInputSummary())
                .contains("【知识定位】", "【答案与评分点】", "【知识速记】", "never reveal final answers",
                        "AI live explanation belongs to chat/dialogue features",
                        "never expose raw JSON keys",
                        "Do not output a complete LaTeX document",
                        "\\documentclass");
        assertThat(gateway.requests().get(1).userInputSummary()).contains("JSON schema");
        assertThat(gateway.requests().get(1).userInputSummary())
                .contains("【知识定位】", "【知识速记】", "no answer/scoring/solution leakage",
                        "printable handouts only");
    }

    @Test
    void usesAnAllowedTeacherSelectedProviderAndModelForTheHandoutDraft() {
        CapturingGateway gateway = new CapturingGateway(List.of(new AiChatResult(
                "openai", "gpt-5.4-mini", 10, 4, 14, "ok", structuredJson("selected model draft"))));
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, true), defaultPolicy());
        TeachingTaskRequest request = new TeachingTaskRequest(
                "req-selected-model", "函数新定义", "函数新定义", 2, null, null, "openai", "gpt-5.4-mini");

        service.draft(request, evidence(), memory());

        assertThat(gateway.requests()).isNotEmpty().allSatisfy(call -> {
                    assertThat(call.providerName()).isEqualTo("openai");
                    assertThat(call.modelCode()).isEqualTo("gpt-5.4-mini");
                });
    }

    @Test
    void removesTeacherOnlyAnswerSectionsFromStudentWorksheet() {
        TeachingAiDraftService.ParsedDraft parsed = TeachingAiDraftService.parseStructuredDraft("""
                {
                  "teacherExplanation": "【知识定位】函数新定义\\n【题型识别】代入求值\\n【方法步骤】先读定义\\n【例题详解】把 $x_0=-1$ 代入。\\n【答案与评分点】答案为 $2$。\\n【易错提醒】不要代错。\\n【课堂追问】D(0) 呢？",
                  "studentHint": "【知识速记】先找到定义里的自变量位置。\\n答案为 $2$，这一行不应进入学生版。\\n【例题详解】把 $x_0=-1$ 代入得到 $2$。\\n【参考解析】先代入再化简。\\n【答案与评分点】答案：$2$，写出代入过程得 2 分。\\n【练习任务】完成同类题，过程写在作答区。",
                  "knowledgePoints": ["函数新定义", "代入求值"],
                  "followUpQuestions": ["D(0) 如何处理？", "条件变化时如何分类？"]
                }
                """);

        assertThat(parsed.structured()).isTrue();
        assertThat(parsed.studentHint())
                .contains("【知识速记】", "【练习任务】", "独立完成")
                .doesNotContain("作答区", "手写区", "留白区",
                        "【例题详解】", "【参考解析】", "【答案与评分点】", "答案：", "答案为", "评分点", "$2$");
        assertThat(parsed.teacherExplanation()).contains("【答案与评分点】", "$2$");
    }

    @Test
    void removesAnswerLeakageFromFollowUpQuestionsUsedByStudentHandouts() {
        TeachingAiDraftService.ParsedDraft parsed = TeachingAiDraftService.parseStructuredDraft("""
                {
                  "teacherExplanation": "【知识定位】函数新定义\\n【题型识别】代入求值\\n【方法步骤】先读定义\\n【例题详解】把 $x_0=-1$ 代入。\\n【答案与评分点】答案为 $2$。\\n【易错提醒】不要代错。\\n【课堂追问】D(0) 呢？",
                  "studentHint": "【知识速记】先找到定义里的自变量位置。\\n【练习任务】完成同类题，过程写在作答区。",
                  "knowledgePoints": ["函数新定义", "代入求值"],
                  "followUpQuestions": ["已知 $D(x_0)$，求 $D(0)$。答案：$2$，写出过程得 2 分。", "条件变化时如何分类？评分点：讨论定义域。"]
                }
                """);

        assertThat(parsed.structured()).isTrue();
        assertThat(parsed.followUpQuestions())
                .containsExactly("已知 $D(x_0)$，求 $D(0)$。", "条件变化时如何分类？")
                .allSatisfy(item -> assertThat(item).doesNotContain("答案", "评分点", "得分", "$2$"));
        assertThat(parsed.studentHint()).doesNotContain("作答区", "手写区", "留白区");
    }

    @Test
    void stripsInternalDebugAndLayoutLinesFromParsedHandoutText() {
        TeachingAiDraftService.ParsedDraft parsed = TeachingAiDraftService.parseStructuredDraft("""
                {
                  "teacherExplanation": "【知识定位】双曲线参数关系。\\n页眉展示主题，颜色使用蓝色。\\n\\\\documentclass{article}\\n\\\\usepackage{fancyhdr}\\nMODEL_CALL_SUCCEEDED openai tokens=100\\n【答案与评分点】由 $c^2=a^2+b^2$ 得 $b^2=16$。",
                  "studentHint": "【知识速记】先写 $c^2=a^2+b^2$。\\n\\\\begin{document}\\nJSON_PARSE_SUCCEEDED tokens=20\\n【练习任务】完成参数计算。\\n解：$b^2=16$。",
                  "knowledgePoints": ["参数关系 $c^2=a^2+b^2$", "debug tokens=10"],
                  "followUpQuestions": ["已知焦距为 10，求 c。解：c=5。", "判断焦点在哪个轴。"]
                }
                """);

        assertThat(parsed.structured()).isTrue();
        assertThat(parsed.teacherExplanation())
                .contains("【知识定位】", "【答案与评分点】", "$b^2=16$")
                .doesNotContain("页眉", "颜色", "MODEL_CALL", "tokens", "\\documentclass", "\\usepackage", "fancyhdr");
        assertThat(parsed.studentHint())
                .contains("【知识速记】", "【练习任务】")
                .doesNotContain("JSON_PARSE", "tokens", "解：", "$b^2=16$", "\\begin{document}");
        assertThat(parsed.knowledgePoints())
                .containsExactly("参数关系 $c^2=a^2+b^2$");
        assertThat(parsed.followUpQuestions())
                .containsExactly("已知焦距为 10，求 c。", "判断焦点在哪个轴。");
    }

    /** A mathematical colour-counting statement is lesson content, not a layout instruction. */
    @Test
    void preservesColoringProblemReasoningWhileStillRemovingActualLayoutRules() {
        TeachingAiDraftService.ParsedDraft parsed = TeachingAiDraftService.parseStructuredDraft("""
                {
                  "teacherExplanation": "【知识定位】地图着色的分类计数。\\n【方法步骤】第3步：相邻区域颜色不同，因此可选颜色数为 $4-r$。\\n页面颜色规则：使用蓝色。\\n【答案与评分点】按相邻关系逐区计数。",
                  "studentHint": "【知识速记】相邻区域不得使用同一颜色。\\n【练习任务】比较颜色相同与不同的分类。",
                  "knowledgePoints": ["地图着色", "相邻区域颜色不同"],
                  "followUpQuestions": ["四种颜色下如何按区域顺序分类？"]
                }
                """);

        assertThat(parsed.structured()).isTrue();
        assertThat(parsed.teacherExplanation()).contains("第3步", "颜色不同", "$4-r$")
                .doesNotContain("页面颜色规则");
        assertThat(parsed.studentHint()).contains("不得使用同一颜色", "颜色相同与不同");
    }

    @Test
    void rejectsUnresolvedTemplatePlaceholdersInsteadOfLettingThemReachThePdf() {
        TeachingAiDraftService.ParsedDraft parsed = TeachingAiDraftService.parseStructuredDraft("""
                {
                  "teacherExplanation": "【知识定位】二次函数。\\n【方法步骤】知识点1：待补充。",
                  "studentHint": "【知识速记】二次函数顶点。\\n【练习任务】题型2。",
                  "knowledgePoints": ["二次函数"],
                  "followUpQuestions": ["求顶点坐标。"]
                }
                """);

        assertThat(parsed.structured()).isFalse();
        assertThat(parsed.parseError()).contains("placeholder");
    }

    @Test
    void formatsQuestionBankEvidenceForPromptWithoutRawAnswerJsonKeys() {
        CapturingGateway gateway = new CapturingGateway(List.of(new AiChatResult(
                "openai",
                "gpt-5.4",
                16,
                8,
                24,
                "ok",
                structuredJson("question bank grounded teacher explanation"))));
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, false), defaultPolicy());
        TeachingEvidence questionBankEvidence = new TeachingEvidence(
                "QUESTION_BANK",
                "双曲线定义与参数关系基础题 / 难度：A 基础",
                "question-1",
                0,
                "已知双曲线焦距为 $10$，且 $2a=6$，求 $a,c,b^2$。\n"
                        + "答案要点：{\"answer\":\"a=3,c=5,b^2=16\","
                        + "\"steps\":[\"由 2a=6 得 a=3\",\"由焦距 10 得 c=5\"],"
                        + "\"scoring\":{\"formula\":\"写出参数关系得分\"},"
                        + "\"extraNote\":\"注意 b^2 不是 b\"}");

        service.draft(request(), List.of(questionBankEvidence), memory());

        assertThat(gateway.requests()).hasSize(1);
        assertThat(gateway.requests().getFirst().userInputSummary())
                .contains("QUESTION_BANK", "A 基础", "答案要点", "答案：$a=3$，$c=5$，$b^2=16$",
                        "步骤：1. 由 $2a=6$ 得 $a=3$", "评分点：补充1：写出参数关系得分",
                        "补充1：注意 $b^2$ 不是 b")
                .doesNotContain("\"answer\"", "\"steps\"", "\"scoring\"", "\"extraNote\"");
    }

    @Test
    void passesVerifiedFigureDescriptionToDraftWithoutLeakingLocalImagePath() {
        CapturingGateway gateway = new CapturingGateway(List.of(new AiChatResult(
                "openai", "gpt-5.4", 12, 6, 18, "ok", structuredJson("figure grounded draft"))));
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, false), defaultPolicy());
        TeachingEvidence figureEvidence = new TeachingEvidence(
                "TEACHER_RESOURCE",
                "涂色问题讲义",
                "teacher-figure-1",
                0,
                "如图，五个区域相邻处不能同色。",
                "C:/Users/doob/AppData/Local/Temp/private-figure.jpg",
                "图像可见文字：区域标号 1、2、3、4、5。图中未给出颜色方案或最终答案。");

        service.draft(request(), List.of(figureEvidence), memory());

        assertThat(gateway.requests()).hasSize(1);
        assertThat(gateway.requests().getFirst().userInputSummary())
                .contains("图像可见文字：区域标号 1、2、3、4、5", "图中未给出颜色方案或最终答案")
                .doesNotContain("C:/Users", "private-figure.jpg");
    }

    @Test
    void injectsDynamicTemplateContextWithoutLocalPathOrLayoutLeakage() {
        CapturingGateway gateway = new CapturingGateway(List.of(new AiChatResult(
                "openai",
                "gpt-5.4",
                18,
                9,
                27,
                "ok",
                structuredJson("template grounded teacher explanation"))));
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, false), defaultPolicy());
        TeachingHandoutTemplateProfile template = new TeachingHandoutTemplateProfile(
                new TeachingHandoutTemplateResponse(
                        "local_inverse_student_sample_v1",
                        "反比例函数学生讲义",
                        "skill_config",
                        "student",
                        "参考本机真实学生讲义。",
                        "学生讲义",
                        "黑白打印讲义",
                        List.of("基础", "提高"),
                        List.of("本机PDF", "反比例函数", "学生版"),
                        "反比例函数（学生版）7658488570078855330.pdf",
                        "C:/Users/doob/Desktop/private/反比例函数（学生版）7658488570078855330.pdf",
                        "首页结构为：教材册别页眉、居中大标题、知识点、题型和连续编号练习，底部页码。",
                        9,
                        4),
                "学生版按连续题号组织，公式必须用 $y=\\frac{k}{x}$。正文不要写页眉、页脚、颜色、PDF规则、AI、token、debug、JSON。",
                true);

        service.draft(request(), evidence(), memory(), template);

        String prompt = gateway.requests().getFirst().userInputSummary();
        String contextLine = lineStarting(prompt, "Template context:");
        String instructionLine = lineStarting(prompt, "Template content instructions:");
        assertThat(contextLine)
                .contains("local_inverse_student_sample_v1", "skill_config", "student", "基础/提高",
                        "本机PDF/反比例函数/学生版", "反比例函数（学生版）7658488570078855330.pdf",
                        "studentBlankSpaceEm=9", "questionGapEm=4",
                        "首页结构为：教材册别")
                .doesNotContain("C:/Users", "private", "页眉", "页脚", "颜色", "PDF规则", "token", "JSON");
        assertThat(instructionLine)
                .contains("连续题号", "$y=\\frac{k}{x}$")
                .doesNotContain("页眉", "页脚", "颜色", "PDF规则", "token", "debug", "JSON");
    }

    @Test
    void removesWorkflowDirectivesFromProblemContextBeforeModelCall() {
        CapturingGateway gateway = new CapturingGateway(List.of(new AiChatResult(
                "openai",
                "gpt-5.4",
                18,
                9,
                27,
                "ok",
                structuredJson("real math explanation"))));
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, false), defaultPolicy());
        TeachingTaskRequest request = new TeachingTaskRequest(
                "req-control-text",
                "生成后保存一次教师版编辑并导出 PDF。",
                "二次函数顶点与对称轴",
                2);

        service.draft(request, evidence(), memory());

        String prompt = gateway.requests().getFirst().userInputSummary();
        assertThat(prompt)
                .contains("二次函数顶点与对称轴")
                .doesNotContain("生成后保存", "教师版编辑", "导出 PDF");
    }

    @Test
    void rotatesToNextProviderWhenJsonRetryStillFails() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("openai", "gpt-5.4", 3, 2, 5, "ok", "bad json"),
                new AiChatResult("openai", "gpt-5.4", 4, 2, 6, "ok", "{\"teacherExplanation\":\"only one field\"}"),
                new AiChatResult("dashscope", "qwen3.6-flash", 7, 5, 12, "ok", structuredJson("qwen fallback"))));
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, true), defaultPolicy());

        TeachingTaskResponse.AiDraft draft = service.draft(request(), evidence(), memory());

        assertThat(draft.structured()).isTrue();
        assertThat(draft.providerName()).isEqualTo("dashscope");
        assertThat(draft.modelCode()).isEqualTo("qwen3.6-flash");
        assertThat(draft.retryCount()).isZero();
        assertThat(draft.totalTokens()).isEqualTo(23);
        assertThat(draft.recoveryEvents()).extracting(TeachingTaskResponse.AiRecoveryEvent::eventType)
                .contains(
                        "JSON_PARSE_FAILED",
                        "PROVIDER_ROTATED",
                        "JSON_PARSE_SUCCEEDED");
        assertThat(draft.recoveryEvents()).filteredOn(event -> "PROVIDER_ROTATED".equals(event.eventType()))
                .extracting(TeachingTaskResponse.AiRecoveryEvent::providerName)
                .containsExactly("dashscope");
        assertThat(gateway.requests()).extracting(AiChatRequest::providerName)
                .containsExactly("openai", "openai", "dashscope");
    }

    @Test
    void retriesTransientGatewayFailureBeforeProviderRotation() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new IllegalStateException("proxy connection reset"),
                new AiChatResult("openai", "gpt-5.4", 8, 5, 13, "ok", structuredJson("proxy recovered teacher explanation"))));
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, false), defaultPolicy());

        TeachingTaskResponse.AiDraft draft = service.draft(request(), evidence(), memory());

        assertThat(draft.structured()).isTrue();
        assertThat(draft.recoveredAfterRetry()).isTrue();
        assertThat(draft.retryCount()).isEqualTo(1);
        assertThat(draft.teacherExplanation()).contains("proxy recovered");
        assertThat(draft.recoveryEvents()).extracting(TeachingTaskResponse.AiRecoveryEvent::eventType)
                .containsExactly(
                        "MODEL_CALL_FAILED",
                        "RETRY_SCHEDULED",
                        "MODEL_CALL_SUCCEEDED",
                        "JSON_PARSE_SUCCEEDED");
        assertThat(draft.recoveryEvents().getFirst().message()).isEqualTo("IllegalStateException");
    }

    @Test
    void keepsRawContentAndParseErrorWhenAllProvidersReturnInvalidJson() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("openai", "gpt-5.4", 3, 2, 5, "ok", "bad json"),
                new AiChatResult("openai", "gpt-5.4", 4, 2, 6, "ok", "{\"teacherExplanation\":\"only one field\"}")));
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, false), defaultPolicy());

        TeachingTaskResponse.AiDraft draft = service.draft(request(), evidence(), memory());

        assertThat(draft.structured()).isFalse();
        assertThat(draft.content()).contains("teacherExplanation");
        assertThat(draft.parseError()).contains("required nonblank teaching fields");
        assertThat(draft.message()).contains("Structured parse failed after 1 retry");
        assertThat(draft.retryCount()).isEqualTo(1);
        assertThat(draft.recoveredAfterRetry()).isFalse();
        assertThat(draft.recoveryEvents()).extracting(TeachingTaskResponse.AiRecoveryEvent::eventType)
                .containsExactly(
                        "MODEL_CALL_SUCCEEDED",
                        "JSON_PARSE_FAILED",
                        "RETRY_SCHEDULED",
                        "MODEL_CALL_SUCCEEDED",
                        "JSON_PARSE_FAILED");
        assertThat(draft.recoveryEvents().getLast().retryable()).isFalse();
    }

    @Test
    void honorsConfiguredRetryCountForJsonRepair() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("openai", "gpt-5.4", 3, 2, 5, "ok", "bad json"),
                new AiChatResult("openai", "gpt-5.4", 4, 2, 6, "ok", "{\"teacherExplanation\":\"only one field\"}"),
                new AiChatResult("openai", "gpt-5.4", 9, 5, 14, "ok", structuredJson("second repair success"))));
        TeachingAiDraftProperties policy = new TeachingAiDraftProperties();
        policy.setMaxRetries(2);
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, false), policy);

        TeachingTaskResponse.AiDraft draft = service.draft(request(), evidence(), memory());

        assertThat(draft.structured()).isTrue();
        assertThat(draft.retryCount()).isEqualTo(2);
        assertThat(draft.maxRetries()).isEqualTo(2);
        assertThat(draft.totalTokens()).isEqualTo(25);
        assertThat(draft.recoveredAfterRetry()).isTrue();
        assertThat(draft.recoveryEvents()).extracting(TeachingTaskResponse.AiRecoveryEvent::eventType)
                .containsExactly(
                        "MODEL_CALL_SUCCEEDED",
                        "JSON_PARSE_FAILED",
                        "RETRY_SCHEDULED",
                        "MODEL_CALL_SUCCEEDED",
                        "JSON_PARSE_FAILED",
                        "RETRY_SCHEDULED",
                        "MODEL_CALL_SUCCEEDED",
                        "JSON_PARSE_SUCCEEDED");
    }

    private static String structuredJson(String teacherExplanation) {
        return """
                {
                  "teacherExplanation": "%s",
                  "studentHint": "Substitute into the definition, then check the condition.",
                  "knowledgePoints": ["new function definition", "domain"],
                  "followUpQuestions": ["How to find D(0)?", "What changes if the sign changes?"]
                }
                """.formatted(teacherExplanation);
    }

    private static TeachingTaskRequest request() {
        return new TeachingTaskRequest(
                "req-ai-structured",
                "Given function f(x) with domain R, find D(-1).",
                "Understand new function definition questions",
                2);
    }

    private static List<TeachingEvidence> evidence() {
        return List.of(new TeachingEvidence(
                "PUBLIC_TEXTBOOK",
                "Textbook A / New function concept",
                "book-a-p101",
                101,
                "For new function definition questions, read condition D(x0), then substitute x0."));
    }

    private static StudentMemoryResponse memory() {
        return new StudentMemoryResponse(false, "", "", "", 0.0, "No reusable memory", List.of());
    }

    private static AiProviderCatalog catalog(boolean openaiEnabled, boolean dashscopeEnabled) {
        AiProviderProperties properties = new AiProviderProperties();
        properties.getOpenai().setApiKey(openaiEnabled ? "test-openai-key" : "");
        properties.getDashscope().setApiKey(dashscopeEnabled ? "test-dashscope-key" : "");
        return new AiProviderCatalog(properties);
    }

    private static TeachingAiDraftProperties defaultPolicy() {
        return new TeachingAiDraftProperties();
    }

    private static String lineStarting(String value, String prefix) {
        for (String line : value.split("\\R")) {
            String stripped = line.strip();
            if (stripped.startsWith(prefix)) {
                return stripped;
            }
        }
        return "";
    }

    private static final class CapturingGateway implements AiChatGateway {

        private final List<Object> outcomes;
        private final List<AiChatRequest> requests = new ArrayList<>();
        private int index;

        private CapturingGateway(List<Object> outcomes) {
            this.outcomes = outcomes;
        }

        @Override
        public AiChatResult call(AiChatRequest request) {
            requests.add(request);
            if (index >= outcomes.size()) {
                throw new IllegalStateException("No test result configured for request " + requests.size());
            }
            Object outcome = outcomes.get(index++);
            if (outcome instanceof RuntimeException exception) {
                throw exception;
            }
            return (AiChatResult) outcome;
        }

        private List<AiChatRequest> requests() {
            return requests;
        }
    }
}
