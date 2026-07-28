package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.agent.service.AgentRunExecutionService;
import com.doob.mathagent.agent.service.AgentRunPlanService;
import com.doob.mathagent.agent.service.AiChatGateway;
import com.doob.mathagent.agent.service.AiChatRequest;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.agent.service.InMemoryAgentConcurrencyGuard;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.agent.service.InMemoryMultiAgentWritingWorkflowStore;
import com.doob.mathagent.agent.service.MultiAgentWritingArtifactExportService;
import com.doob.mathagent.agent.service.MultiAgentWritingService;
import com.doob.mathagent.agent.vo.MultiAgentWritingArtifactExportResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class MultiAgentWritingArtifactExportServiceTest {

    @Test
    void exportsOwnedArtifactAsMarkdownWithChecksumAndTtl() throws Exception {
        MultiAgentWritingService writingService = writingService();
        RequestSubject subject = subject();
        String workflowId = completedWorkflow(writingService, subject);
        MultiAgentWritingArtifactExportService exportService = new MultiAgentWritingArtifactExportService(
                writingService,
                Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5));

        MultiAgentWritingArtifactExportResponse response = exportService.export(workflowId, "md", subject);

        byte[] bytes = Base64.getDecoder().decode(response.base64Content());
        String markdown = new String(bytes, StandardCharsets.UTF_8);
        assertThat(response.workflowId()).isEqualTo(workflowId);
        assertThat(response.format()).isEqualTo("markdown");
        assertThat(response.fileName()).endsWith(".md");
        assertThat(response.mimeType()).contains("text/markdown");
        assertThat(response.byteSize()).isEqualTo(bytes.length);
        assertThat(response.sha256()).isEqualTo(sha256(bytes));
        assertThat(response.expiresAt()).isEqualTo(Instant.parse("2026-07-01T00:05:00Z"));
        assertThat(markdown).contains("Teacher Draft", "Student Worksheet", "Lecture Cards");
    }

    @Test
    void exportsOwnedArtifactAsLatexWithMathPreserved() throws Exception {
        MultiAgentWritingService writingService = writingService();
        RequestSubject subject = subject();
        String workflowId = completedWorkflow(writingService, subject);
        MultiAgentWritingArtifactExportService exportService = new MultiAgentWritingArtifactExportService(
                writingService,
                Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5));

        MultiAgentWritingArtifactExportResponse response = exportService.export(workflowId, "tex", subject);

        byte[] bytes = Base64.getDecoder().decode(response.base64Content());
        String latex = new String(bytes, StandardCharsets.UTF_8);
        assertThat(response.workflowId()).isEqualTo(workflowId);
        assertThat(response.format()).isEqualTo("latex");
        assertThat(response.fileName()).endsWith(".tex");
        assertThat(response.mimeType()).contains("application/x-tex");
        assertThat(response.byteSize()).isEqualTo(bytes.length);
        assertThat(response.sha256()).isEqualTo(sha256(bytes));
        assertThat(response.expiresAt()).isEqualTo(Instant.parse("2026-07-01T00:05:00Z"));
        assertThat(latex)
                .contains("\\documentclass[UTF8]{ctexart}")
                .contains("\\title{\\color{MathAgentNavy}Teacher Draft}")
                .contains("\\item Function zero method: $f(x)=0$")
                .contains("\\end{document}");
    }

    @Test
    void exportsOwnedArtifactAsPdfForReview() throws Exception {
        // The production contract compiles in Docker where Noto CJK is installed. Windows unit execution may have
        // XeLaTeX but deliberately lacks that font, so it cannot be used as a truthful rendering result.
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv("MATH_AGENT_TEST_XELATEX_NOTO")),
                "PDF rendering is verified by the Docker acceptance environment with Noto CJK");
        MultiAgentWritingService writingService = writingService();
        RequestSubject subject = subject();
        String workflowId = completedWorkflow(writingService, subject);
        MultiAgentWritingArtifactExportService exportService = new MultiAgentWritingArtifactExportService(
                writingService,
                Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5));

        MultiAgentWritingArtifactExportResponse response = exportService.export(workflowId, "pdf", subject);

        byte[] bytes = Base64.getDecoder().decode(response.base64Content());
        assertThat(response.format()).isEqualTo("pdf");
        assertThat(response.fileName()).endsWith(".pdf");
        assertThat(response.mimeType()).isEqualTo("application/pdf");
        assertThat(response.byteSize()).isEqualTo(bytes.length);
        assertThat(response.sha256()).isEqualTo(sha256(bytes));
        assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void exportsOwnedArtifactAsZipWithManifestAndStageFiles() throws Exception {
        MultiAgentWritingService writingService = writingService();
        RequestSubject subject = subject();
        String workflowId = completedWorkflow(writingService, subject);
        MultiAgentWritingArtifactExportService exportService = new MultiAgentWritingArtifactExportService(
                writingService,
                Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5));

        MultiAgentWritingArtifactExportResponse response = exportService.export(workflowId, "zip", subject);

        byte[] bytes = Base64.getDecoder().decode(response.base64Content());
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (java.util.zip.ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        assertThat(response.format()).isEqualTo("zip");
        assertThat(response.fileName()).endsWith(".zip");
        assertThat(response.mimeType()).isEqualTo("application/zip");
        assertThat(response.sha256()).isEqualTo(sha256(bytes));
        assertThat(names).contains("merged.md", "manifest.txt", "stages/resource_curation.md",
                "stages/teacher_writer.md", "stages/student_writer.md", "stages/lecture_writer.md");
    }

    /**
     * Runs a completed workflow with deterministic model content.
     */
    private static String completedWorkflow(MultiAgentWritingService writingService, RequestSubject subject) {
        return writingService.run(
                        new MultiAgentWritingRequest(
                                "teacher handout",
                                "function zero",
                                List.of("PUBLIC_TEXTBOOK:function:zero"),
                                false,
                                "dashscope",
                                "qwen3.6-flash"),
                        subject)
                .workflowId();
    }

    /**
     * Creates the backend subject used by the owner-visible export.
     */
    private static RequestSubject subject() {
        return new RequestSubject("school-a", "teacher", "teacher-1", "device-1");
    }

    /**
     * Builds a multi-agent writing service with real planner/executor policy and controlled provider results.
     */
    private static MultiAgentWritingService writingService() {
        AiProviderCatalog catalog = providerCatalog();
        return new MultiAgentWritingService(
                new AgentRunPlanService(catalog),
                new AgentRunExecutionService(
                        new InMemoryAgentTraceStore(),
                        new InMemoryAgentConcurrencyGuard(),
                        gateway(),
                        catalog,
                        Clock.systemUTC()),
                new InMemoryMultiAgentWritingWorkflowStore(),
                new org.springframework.core.task.SyncTaskExecutor());
    }

    /**
     * Creates enabled provider settings for deterministic unit execution.
     */
    private static AiProviderCatalog providerCatalog() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("dashscope");
        properties.getDashscope().setApiKey("dashscope-key");
        properties.getDashscope().setChatModel("qwen3.6-flash");
        return new AiProviderCatalog(properties);
    }

    /**
     * Creates a deterministic gateway with one response per writing stage.
     */
    private static AiChatGateway gateway() {
        return new AiChatGateway() {
            @Override
            public AiChatResult call(AiChatRequest request) {
                String prompt = request.userInputSummary();
                if (prompt.contains("stage=resource_curation")) {
                    return result("resource recorded", "{\"content\":\"# Resource Curation\\n教材依据：函数概念\"}");
                }
                if (prompt.contains("stage=teacher_writer")) {
                    return result("teacher recorded", "{\"teacherExplanation\":\"# Teacher Draft\\n- Function zero method: $f(x)=0$\"}");
                }
                if (prompt.contains("stage=student_writer")) {
                    return result("student recorded", "{\"studentWorksheet\":\"# Student Worksheet\\n- Solve $f(x)=0$.\\n\\n____\"}");
                }
                if (prompt.contains("stage=lecture_writer")) {
                    return result("lecture recorded", "{\"lectureCards\":\"# Lecture Cards\\n题目：$f(x)=0$\"}");
                }
                throw new IllegalArgumentException("Unexpected writing stage prompt: " + prompt);
            }
        };
    }

    /** Stage-addressed deterministic fixture remains safe after writers became genuinely parallel. */
    private static AiChatResult result(String message, String content) {
        return new AiChatResult("dashscope", "qwen3.6-flash", 8, 4, 12, message, content);
    }

    /**
     * Computes SHA-256 for assertion.
     */
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
