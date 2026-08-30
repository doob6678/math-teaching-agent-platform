package com.doob.mathagent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression coverage for Java-owned source-image alias bindings used by PDF export. */
class MultiAgentWritingArtifactExportServiceImagePlacementTest {

    private static final String FIRST_ROW = "![source-image:run-a-image-001](IMAJES/image-001.png)";
    private static final String SECOND_ROW = "![source-image:run-a-image-002](IMAJES/image-002.jpg)";

    @Test
    void resolvesOnlyRowsRecordedInResourceCurationEvidence() {
        MultiAgentWritingArtifact artifact = artifact("""
                {"items":[
                  {"sourceDocumentId":"doc-functions","imageRefs":[{"markdownLine":"%s","logicalPath":"函数/IMAJES/image-001.png"}]},
                  {"sourceDocumentId":"doc-geometry","imageRefs":[{"markdownLine":"%s","logicalPath":"几何/IMAJES/image-002.jpg"}]}
                ]}
                """.formatted(FIRST_ROW, SECOND_ROW));

        List<?> bindings = bindings(artifact);

        assertThat(bindings).hasSize(2);
        assertThat(bindings.toString()).contains(
                "doc-functions", "doc-geometry", "函数/IMAJES/image-001.png", "几何/IMAJES/image-002.jpg");
        assertThat(bindings.toString()).doesNotContain("asset-", "http", "/app/");
    }

    @Test
    void removesInternalEvidenceReferencesFromVisibleExportContent() {
        String sanitized = stripInternalEvidenceReferences(
                "题目依据 ev_0123456789abcdef0123456789abcdef 说明，保留 ![source-image:run-a-image-001](IMAJES/image-001.png)。\n证据引用\n；");

        assertThat(sanitized).doesNotContain("ev_0123456789abcdef0123456789abcdef", "证据引用");
        assertThat(sanitized).contains("题目依据", "![source-image:run-a-image-001](IMAJES/image-001.png)");
    }

    @Test
    void rejectsAmbiguousAliasBoundToTwoLogicalAssets() {
        MultiAgentWritingArtifact artifact = artifact("""
                {"items":[
                  {"sourceDocumentId":"doc-functions","imageRefs":[{"markdownLine":"%s","logicalPath":"函数/IMAJES/image-001.png"}]},
                  {"sourceDocumentId":"doc-geometry","imageRefs":[{"markdownLine":"%s","logicalPath":"几何/IMAJES/image-001.png"}]}
                ]}
                """.formatted(FIRST_ROW, FIRST_ROW));

        assertThatThrownBy(() -> bindings(artifact))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("多个授权资源");
    }

    @Test
    void recordsCanonicalScopeAndQuestionNumberOnGaokaoFigureBindings() {
        MultiAgentWritingArtifact artifact = artifact("""
                {"items":[
                  {"sourceDocumentId":"paper-ref","sourceScope":"CANONICAL_MATH_PAPER","canonicalQuestionNumber":"17",
                   "imageRefs":[{"markdownLine":"%s","logicalPath":"figures/q-017-01.png"}]}
                ]}
                """.formatted(FIRST_ROW));

        List<?> bindings = bindings(artifact);

        assertThat(bindings).hasSize(1);
        assertThat(bindings.toString()).contains("CANONICAL_MATH_PAPER", "17", "figures/q-017-01.png");
    }

    private static MultiAgentWritingArtifact artifact(String evidence) {
        MultiAgentWritingArtifact.StageArtifact stage = new MultiAgentWritingArtifact.StageArtifact(
                "resource_curation", "broker", "trace", "java", "", "COMPLETED", evidence, List.of(), List.of());
        return new MultiAgentWritingArtifact("workflow", "tenant", "teacher", "teacher", "COMPLETED",
                null, List.of(stage), List.of(), "");
    }

    private static String stripInternalEvidenceReferences(String content) {
        try {
            Method method = MultiAgentWritingArtifactExportService.class.getDeclaredMethod(
                    "stripInternalEvidenceReferences", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, content);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<?> bindings(MultiAgentWritingArtifact artifact) {
        try {
            Method method = MultiAgentWritingArtifactExportService.class.getDeclaredMethod(
                    "authorizedSourceImages", MultiAgentWritingArtifact.class);
            method.setAccessible(true);
            return (List<?>) method.invoke(null, artifact);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
}
