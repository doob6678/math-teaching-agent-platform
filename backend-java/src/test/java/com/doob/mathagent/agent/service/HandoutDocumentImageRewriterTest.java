package com.doob.mathagent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies source-row image labels are rewritten without exposing asset ids or paths outside the document row. */
class HandoutDocumentImageRewriterTest {
    private static final RequestSubject SUBJECT = new RequestSubject("tenant", "teacher", "teacher-1", "test");

    @Test
    void rewritesMultipleFormatsAndKeepsTargetsAndText() {
        String first = "前文\n\n![](IMAJES/image-001.jpg)";
        String second = "中间\n\n![原图](IMAJES/image-002.jpeg)\n\n![另一图](IMAJES/image-003.png)";
        List<TeacherDocumentBlockResponse> blocks = List.of(
                block("doc-a", "a.md", first, "[{\"markdownLine\":\"![](IMAJES/image-001.jpg)\",\"logicalPath\":\"a/IMAJES/image-001.jpg\",\"mimeType\":\"image/jpeg\"}]"),
                block("doc-a", "a.md", second, "[{\"markdownLine\":\"![原图](IMAJES/image-002.jpeg)\",\"logicalPath\":\"a/IMAJES/image-002.jpeg\",\"mimeType\":\"image/jpeg\"},{\"markdownLine\":\"![另一图](IMAJES/image-003.png)\",\"logicalPath\":\"a/IMAJES/image-003.png\",\"mimeType\":\"image/png\"}]")
        );

        List<TeacherDocumentBlockResponse> result = new HandoutDocumentImageRewriter().rewrite(
                "run-a", blocks, SUBJECT, (documentId, logicalPath, subject) -> documentId.equals("doc-a"));

        assertThat(result.get(0).rawText()).contains("![source-image:").contains("(IMAJES/image-001.jpg)").contains("前文");
        assertThat(result.get(1).rawText()).contains("(IMAJES/image-002.jpeg)", "(IMAJES/image-003.png)")
                .contains("source-image:").contains("中间");
        assertThat(result.get(0).rawText()).doesNotContain("asset-").doesNotContain("/app/").doesNotContain("http");
    }

    @Test
    void sameRelativeNameInDifferentDocumentsGetsDifferentRunAliases() {
        String line = "![](IMAJES/image-001.jpg)";
        List<TeacherDocumentBlockResponse> blocks = List.of(
                block("doc-a", "a.md", line, ref(line, "a/IMAJES/image-001.jpg")),
                block("doc-b", "b.md", line, ref(line, "b/IMAJES/image-001.jpg")));

        List<TeacherDocumentBlockResponse> result = new HandoutDocumentImageRewriter().rewrite(
                "run-a", blocks, SUBJECT, (documentId, logicalPath, subject) -> true);

        String firstAlias = result.get(0).rawText().substring(result.get(0).rawText().indexOf("![") + 2,
                result.get(0).rawText().indexOf("](IMAJES"));
        String secondAlias = result.get(1).rawText().substring(result.get(1).rawText().indexOf("![") + 2,
                result.get(1).rawText().indexOf("](IMAJES"));
        assertThat(firstAlias).isNotEqualTo(secondAlias);
    }

    @Test
    void keepsSourceOrderedImageBlocksWithinTheVisibleImageBudget() {
        List<TeacherDocumentBlockResponse> blocks = java.util.stream.IntStream.rangeClosed(1, 51)
                .mapToObj(index -> {
                    String target = String.format("IMAJES/image-%03d.jpg", index);
                    String line = "![](" + target + ")";
                    return block("doc-a", "a.md", "图片 " + index + "\n" + line,
                            ref(line, "a/" + target, "image/jpeg"));
                })
                .toList();

        List<TeacherDocumentBlockResponse> result = new HandoutDocumentImageRewriter("source-image", 50).rewrite(
                "run-a", blocks, SUBJECT, (documentId, logicalPath, subject) -> true);

        assertThat(result).hasSize(50);
        assertThat(result.getFirst().rawText()).contains("(IMAJES/image-001.jpg)").contains("![source-image:");
        assertThat(result.getLast().rawText()).contains("(IMAJES/image-050.jpg)").contains("![source-image:");
        assertThat(result).allSatisfy(block -> assertThat(block.rawText()).doesNotContain("IMAJES/image-051.jpg"));
    }

    @Test
    void rejectsTraversalAndUnsupportedReferences() {
        assertThatThrownBy(() -> rewrite("![x](../outside.jpg)", "doc/IMAJES/outside.jpg", "image/jpeg"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unsafe");
        assertThatThrownBy(() -> rewrite("![x](IMAJES/image-001.gif)", "doc/IMAJES/image-001.gif", "image/gif"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unsupported");
        assertThatThrownBy(() -> rewrite("![x](https://example.test/a.png)", "doc/IMAJES/a.png", "image/png"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unsafe");
    }

    @Test
    void rejectsUnauthorizedLogicalPath() {
        assertThatThrownBy(() -> new HandoutDocumentImageRewriter().rewrite(
                "run-a", List.of(block("doc-a", "a.md", "![x](IMAJES/a.png)", ref("![x](IMAJES/a.png)", "doc/IMAJES/a.png"))),
                SUBJECT, (documentId, logicalPath, subject) -> false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unavailable");
    }

    private static List<TeacherDocumentBlockResponse> rewrite(String line, String logicalPath, String mimeType) {
        return new HandoutDocumentImageRewriter().rewrite("run-a", List.of(block("doc-a", "a.md", line,
                ref(line, logicalPath, mimeType))), SUBJECT, (documentId, path, subject) -> true);
    }

    private static String ref(String line, String logicalPath) {
        return ref(line, logicalPath, "image/png");
    }

    private static String ref(String line, String logicalPath, String mimeType) {
        try {
            return new ObjectMapper().writeValueAsString(List.of(java.util.Map.of(
                    "markdownLine", line, "logicalPath", logicalPath, "mimeType", mimeType)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static TeacherDocumentBlockResponse block(String documentId, String sourcePath, String text, String refs) {
        return new TeacherDocumentBlockResponse("block-" + documentId, documentId, sourcePath, "markdown", 1,
                "", "", null, "", sourcePath, "reference", text, text, refs, "[]", "[]", "[]", "checksum", 1D, "active");
    }
}
