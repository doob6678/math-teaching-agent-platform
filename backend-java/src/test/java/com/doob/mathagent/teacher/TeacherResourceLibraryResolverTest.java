package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teacher.service.TeacherResourceLibraryResolver;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeacherResourceLibraryResolverTest {

    @Test
    void infersSpecializedLibrariesFromLocalPathMetadataWithoutMatchingTeacherResourceFallback() {
        TeacherResourceDocumentResponse qqDocument = new TeacherResourceDocumentResponse(
                "doc-qq",
                "school-a",
                "teacher-1",
                "local_path",
                "Runtime QQ bundle package",
                null,
                "C:/workspace/runtime-authored/02-qq-bundle-vector",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of());
        TeacherResourceDocumentResponse feishuDocument = new TeacherResourceDocumentResponse(
                "doc-feishu",
                "school-a",
                "teacher-1",
                "local_path",
                "Runtime Feishu method package",
                null,
                "C:/workspace/runtime-authored/03-feishu-method-probability",
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of());
        TeacherResourceDocumentResponse genericDocument = new TeacherResourceDocumentResponse(
                "doc-generic",
                "school-a",
                "teacher-1",
                "local_path",
                "Teacher owned vector notes",
                null,
                "C:/workspace/runtime-authored/space-vector-handout",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of());

        assertThat(TeacherResourceLibraryResolver.effectiveLibrary(qqDocument)).isEqualTo("qq_bundle");
        assertThat(TeacherResourceLibraryResolver.effectiveLibrary(feishuDocument)).isEqualTo("feishu");
        assertThat(TeacherResourceLibraryResolver.effectiveLibrary(genericDocument)).isEqualTo("teacher_resource");
        assertThat(TeacherResourceLibraryResolver.matchesAny(qqDocument, List.of("teacher_resource"))).isFalse();
        assertThat(TeacherResourceLibraryResolver.matchesAny(feishuDocument, List.of("teacher_resource"))).isFalse();
        assertThat(TeacherResourceLibraryResolver.matchesAny(genericDocument, List.of("teacher_resource"))).isTrue();
        assertThat(TeacherResourceLibraryResolver.matchesAny(qqDocument, List.of("qq_bundle"))).isTrue();
        assertThat(TeacherResourceLibraryResolver.matchesAny(feishuDocument, List.of("feishu"))).isTrue();
    }

    @Test
    void marksSystemKnowledgeBaseAsSystemReferenceInsteadOfTeacherResource() {
        TeacherResourceDocumentResponse systemDocument = new TeacherResourceDocumentResponse(
                "doc-system",
                "school-a",
                "admin-1",
                "local_path",
                "design-system-docs",
                null,
                "C:/workspace/seed-resources/design-system-docs",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of());

        assertThat(TeacherResourceLibraryResolver.effectiveLibrary(systemDocument)).isEqualTo("system_reference");
        assertThat(TeacherResourceLibraryResolver.matchesAny(systemDocument, List.of("teacher_resource"))).isFalse();
    }

    @Test
    void marksBenchmarkAndSyntheticArtifactsAsSystemReferenceInsteadOfTeacherResource() {
        TeacherResourceDocumentResponse benchmarkDocument = new TeacherResourceDocumentResponse(
                "doc-benchmark",
                "school-a",
                "admin-1",
                "local_path",
                "benchmark-high-school-math-20260707",
                null,
                "C:/workspace/.local-storage/benchmark-math-resources",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of());
        TeacherResourceDocumentResponse syntheticDocument = new TeacherResourceDocumentResponse(
                "doc-synthetic",
                "school-a",
                "admin-1",
                "local_path",
                "synthetic-natural-math-benchmark-1783401750",
                null,
                "C:/workspace/output/benchmarks/synthetic-full-chain-20260707-132230/synthetic-resource",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of());

        assertThat(TeacherResourceLibraryResolver.effectiveLibrary(benchmarkDocument)).isEqualTo("system_reference");
        assertThat(TeacherResourceLibraryResolver.effectiveLibrary(syntheticDocument)).isEqualTo("system_reference");
        assertThat(TeacherResourceLibraryResolver.matchesAny(benchmarkDocument, List.of("teacher_resource"))).isFalse();
        assertThat(TeacherResourceLibraryResolver.matchesAny(syntheticDocument, List.of("teacher_resource"))).isFalse();
    }
}
