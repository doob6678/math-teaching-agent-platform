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
        assertThat(markdown).contains("Teacher Draft", "Quality Review", "Formatted handout");
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
                .contains("\\section*{Metadata}")
                .contains("\\section*{Teacher Draft}")
                .contains("\\item Function zero method: $f(x)=0$")
                .contains("\\subsection*{Quality Review}")
                .contains("\\subsubsection*{Final Handout}")
                .contains("\\end{document}");
    }

    @Test
    void exportsOwnedArtifactAsPdfForReview() throws Exception {
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
        assertThat(names).contains("merged.md", "manifest.txt", "stages/draft.md", "stages/review.md", "stages/format.md");
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
        List<AiChatResult> results = new ArrayList<>(List.of(
                new AiChatResult(
                        "dashscope",
                        "qwen3.6-flash",
                        11,
                        7,
                        18,
                        "draft recorded",
                        "# Teacher Draft\n- Function zero method: $f(x)=0$"),
                new AiChatResult(
                        "dashscope",
                        "qwen3.6-flash",
                        9,
                        5,
                        14,
                        "review recorded",
                        "{\"review\":\"## Quality Review\\nUse domain and monotonicity.\",\"status\":\"ok\"}"),
                new AiChatResult(
                        "dashscope",
                        "qwen3.6-flash",
                        8,
                        4,
                        12,
                        "format recorded",
                        "### Final Handout\nFormatted handout")));
        return new AiChatGateway() {
            @Override
            public AiChatResult call(AiChatRequest request) {
                return results.removeFirst();
            }
        };
    }

    /**
     * Computes SHA-256 for assertion.
     */
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
