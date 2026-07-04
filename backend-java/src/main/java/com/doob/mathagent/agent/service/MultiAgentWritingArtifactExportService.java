package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.vo.MultiAgentWritingArtifactExportResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Creates short-lived export payloads for owner-visible multi-agent writing artifacts.
 */
@Service
public class MultiAgentWritingArtifactExportService {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final MultiAgentWritingService writingService;
    private final Clock clock;
    private final Duration ttl;

    /**
     * Creates a production export service.
     *
     * @param writingService workflow service used for owner-visible artifact lookup
     * @param ttlMinutes temporary export lifetime in minutes
     */
    @Autowired
    public MultiAgentWritingArtifactExportService(
            MultiAgentWritingService writingService,
            @Value("${math-agent.agent.writing.artifact-export-ttl-minutes:30}") long ttlMinutes) {
        this(writingService, Clock.systemUTC(), Duration.ofMinutes(Math.max(1, ttlMinutes)));
    }

    /**
     * Creates a testable export service.
     *
     * @param writingService workflow service used for owner-visible artifact lookup
     * @param clock clock used to calculate expiration
     * @param ttl temporary export lifetime
     */
    public MultiAgentWritingArtifactExportService(
            MultiAgentWritingService writingService,
            Clock clock,
            Duration ttl) {
        this.writingService = writingService;
        this.clock = clock;
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
    }

    /**
     * Exports a workflow artifact as Markdown, LaTeX, or ZIP bytes encoded for MCP transport.
     *
     * @param workflowId workflow id
     * @param format requested format: markdown, latex, or zip
     * @param subject backend-resolved owner subject
     * @return export response containing base64 bytes and checksum
     */
    public MultiAgentWritingArtifactExportResponse export(
            String workflowId,
            String format,
            RequestSubject subject) {
        MultiAgentWritingArtifact artifact = writingService.artifact(workflowId, subject);
        String normalizedFormat = normalizeFormat(format);
        ExportPayload payload = switch (normalizedFormat) {
            case "zip" -> zipPayload(artifact);
            case "latex" -> latexPayload(artifact);
            case "markdown" -> markdownPayload(artifact);
            default -> throw new IllegalArgumentException("Unsupported artifact export format: " + normalizedFormat);
        };
        String exportId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now(clock).plus(ttl);
        return new MultiAgentWritingArtifactExportResponse(
                exportId,
                artifact.workflowId(),
                normalizedFormat,
                payload.fileName(),
                payload.mimeType(),
                payload.bytes().length,
                sha256(payload.bytes()),
                Base64.getEncoder().encodeToString(payload.bytes()),
                expiresAt);
    }

    /**
     * Creates a LaTeX payload from the merged artifact body for frontend preview or download.
     */
    private static ExportPayload latexPayload(MultiAgentWritingArtifact artifact) {
        String body = latexDocument(artifact);
        return new ExportPayload(
                safeFileStem(artifact.workflowId()) + ".tex",
                "application/x-tex; charset=UTF-8",
                body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Creates a Markdown payload from the merged artifact body.
     */
    private static ExportPayload markdownPayload(MultiAgentWritingArtifact artifact) {
        String body = artifact.mergedMarkdown().isBlank()
                ? "# Multi-agent writing artifact\n\nNo generated content is available yet.\n"
                : artifact.mergedMarkdown() + "\n";
        return new ExportPayload(
                safeFileStem(artifact.workflowId()) + ".md",
                "text/markdown; charset=UTF-8",
                body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Creates a ZIP payload with merged, per-stage, and manifest files.
     */
    private static ExportPayload zipPayload(MultiAgentWritingArtifact artifact) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                put(zip, "merged.md", (artifact.mergedMarkdown() + "\n").getBytes(StandardCharsets.UTF_8));
                for (MultiAgentWritingArtifact.StageArtifact stage : safeStages(artifact.stages())) {
                    put(zip,
                            "stages/" + safeFileStem(stage.stageCode()) + ".md",
                            (stage.generatedContent() + "\n").getBytes(StandardCharsets.UTF_8));
                }
                put(zip, "manifest.txt", manifest(artifact).getBytes(StandardCharsets.UTF_8));
            }
            return new ExportPayload(
                    safeFileStem(artifact.workflowId()) + ".zip",
                    "application/zip",
                    bytes.toByteArray());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Failed to create multi-agent writing ZIP export", exception);
        }
    }

    /**
     * Writes one ZIP entry.
     */
    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    /**
     * Builds an offline manifest for exported artifacts.
     */
    private static String manifest(MultiAgentWritingArtifact artifact) {
        return """
                workflowId=%s
                tenantId=%s
                subjectType=%s
                subjectId=%s
                status=%s
                totalTokens=%d
                stageCount=%d
                """.formatted(
                artifact.workflowId(),
                artifact.tenantId(),
                artifact.subjectType(),
                artifact.subjectId(),
                artifact.status(),
                artifact.totalUsage().totalTokens(),
                safeStages(artifact.stages()).size());
    }

    /**
     * Converts the merged Markdown-like artifact into a conservative LaTeX document.
     */
    private static String latexDocument(MultiAgentWritingArtifact artifact) {
        String content = artifact.mergedMarkdown().isBlank()
                ? "No generated content is available yet."
                : artifact.mergedMarkdown();
        StringBuilder latex = new StringBuilder();
        latex.append("""
                \\documentclass[UTF8]{ctexart}
                \\usepackage{amsmath,amssymb,geometry}
                \\usepackage{enumitem}
                \\geometry{a4paper,margin=2cm}
                \\setlist{nosep,leftmargin=2em}
                \\title{Multi-agent Writing Artifact}
                \\date{}
                \\begin{document}
                \\maketitle

                """);
        latex.append("\\section*{Metadata}\n");
        latex.append("\\begin{itemize}\n");
        latex.append("\\item Workflow ID: ").append(escapeLatexText(artifact.workflowId())).append('\n');
        latex.append("\\item Status: ").append(escapeLatexText(artifact.status())).append('\n');
        latex.append("\\item Total tokens: ").append(artifact.totalUsage().totalTokens()).append('\n');
        latex.append("\\end{itemize}\n\n");
        appendMarkdownAsLatex(latex, content);
        latex.append("\n\\end{document}\n");
        return latex.toString();
    }

    /**
     * Appends a compact Markdown subset as LaTeX while preserving inline math delimiters.
     */
    private static void appendMarkdownAsLatex(StringBuilder latex, String markdown) {
        boolean inItemize = false;
        for (String rawLine : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = rawLine.stripTrailing();
            if (line.isBlank()) {
                if (inItemize) {
                    latex.append("\\end{itemize}\n");
                    inItemize = false;
                }
                latex.append('\n');
                continue;
            }
            if (line.startsWith("### ")) {
                if (inItemize) {
                    latex.append("\\end{itemize}\n");
                    inItemize = false;
                }
                latex.append("\\subsubsection*{").append(escapeLatexText(line.substring(4).strip())).append("}\n");
            } else if (line.startsWith("## ")) {
                if (inItemize) {
                    latex.append("\\end{itemize}\n");
                    inItemize = false;
                }
                latex.append("\\subsection*{").append(escapeLatexText(line.substring(3).strip())).append("}\n");
            } else if (line.startsWith("# ")) {
                if (inItemize) {
                    latex.append("\\end{itemize}\n");
                    inItemize = false;
                }
                latex.append("\\section*{").append(escapeLatexText(line.substring(2).strip())).append("}\n");
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                if (!inItemize) {
                    latex.append("\\begin{itemize}\n");
                    inItemize = true;
                }
                latex.append("\\item ").append(escapeLatexTextPreservingMath(line.substring(2).strip())).append('\n');
            } else {
                if (inItemize) {
                    latex.append("\\end{itemize}\n");
                    inItemize = false;
                }
                latex.append(escapeLatexTextPreservingMath(line)).append("\\par\n");
            }
        }
        if (inItemize) {
            latex.append("\\end{itemize}\n");
        }
    }

    /**
     * Escapes normal text segments while leaving $...$ and $$...$$ math untouched.
     */
    private static String escapeLatexTextPreservingMath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder();
        int index = 0;
        while (index < value.length()) {
            int mathStart = value.indexOf('$', index);
            if (mathStart < 0) {
                escaped.append(escapeLatexText(value.substring(index)));
                break;
            }
            escaped.append(escapeLatexText(value.substring(index, mathStart)));
            boolean displayMath = mathStart + 1 < value.length() && value.charAt(mathStart + 1) == '$';
            String delimiter = displayMath ? "$$" : "$";
            int contentStart = mathStart + delimiter.length();
            int mathEnd = value.indexOf(delimiter, contentStart);
            if (mathEnd < 0) {
                escaped.append(escapeLatexText(value.substring(mathStart)));
                break;
            }
            escaped.append(value, mathStart, mathEnd + delimiter.length());
            index = mathEnd + delimiter.length();
        }
        return escaped.toString();
    }

    /**
     * Escapes text for LaTeX outside math mode.
     */
    private static String escapeLatexText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\textbackslash{}");
                case '{' -> escaped.append("\\{");
                case '}' -> escaped.append("\\}");
                case '&' -> escaped.append("\\&");
                case '%' -> escaped.append("\\%");
                case '#' -> escaped.append("\\#");
                case '_' -> escaped.append("\\_");
                case '^' -> escaped.append("\\textasciicircum{}");
                case '~' -> escaped.append("\\textasciitilde{}");
                case '$' -> escaped.append("\\$");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    /**
     * Normalizes external format aliases.
     */
    private static String normalizeFormat(String format) {
        if (format == null || format.isBlank()) {
            return "markdown";
        }
        return switch (format.strip().toLowerCase()) {
            case "md", "markdown" -> "markdown";
            case "tex", "latex" -> "latex";
            case "zip" -> "zip";
            default -> format.strip().toLowerCase();
        };
    }

    /**
     * Returns stage list or an empty list.
     */
    private static List<MultiAgentWritingArtifact.StageArtifact> safeStages(
            List<MultiAgentWritingArtifact.StageArtifact> stages) {
        return stages == null ? List.of() : stages;
    }

    /**
     * Converts ids into deterministic file names without path traversal.
     */
    private static String safeFileStem(String value) {
        String safe = value == null || value.isBlank() ? "artifact" : value.strip();
        safe = safe.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_")
                .replaceAll("^\\.+", "")
                .strip();
        return safe.isBlank() ? "artifact" : safe;
    }

    /**
     * Computes SHA-256 for exported bytes.
     */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
    }

    /**
     * Internal export payload before it is wrapped for MCP transport.
     */
    private record ExportPayload(String fileName, String mimeType, byte[] bytes) {
    }
}
