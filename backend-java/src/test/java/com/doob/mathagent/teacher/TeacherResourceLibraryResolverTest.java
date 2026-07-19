package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teacher.support.TeacherResourceLibraryResolver;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
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

    @Test
    void keepsRuntimeAuthoredTeacherPackAsTeacherResourceEvenInsideBenchmarkOutputTree() {
        TeacherResourceDocumentResponse runtimeTeacherPack = new TeacherResourceDocumentResponse(
                "doc-runtime-pack",
                "school-a",
                "teacher-1",
                "local_path",
                "runtime-teacher-resource-pack-v6",
                null,
                "C:/workspace/output/benchmarks/live-two-stage-teacher-generated-100/runtime-authored/teacher-resource-pack",
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of());

        assertThat(TeacherResourceLibraryResolver.effectiveLibrary(runtimeTeacherPack)).isEqualTo("teacher_resource");
        assertThat(TeacherResourceLibraryResolver.matchesAny(runtimeTeacherPack, List.of("teacher_resource"))).isTrue();
        assertThat(TeacherResourceLibraryResolver.matchesAny(runtimeTeacherPack, List.of("system_reference"))).isFalse();
    }

    @Test
    void keepsRuntimeAuthoredSpecializedLibrariesSearchableInsideBenchmarkOutputTree() {
        TeacherResourceDocumentResponse runtimeQq = new TeacherResourceDocumentResponse(
                "doc-runtime-qq",
                "school-a",
                "admin-1",
                "local_path",
                "runtime-qq-bundle-vector",
                null,
                "C:/workspace/output/benchmarks/live/runtime-authored/02-qq-bundle-vector",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of());
        TeacherResourceDocumentResponse runtimeFeishu = new TeacherResourceDocumentResponse(
                "doc-runtime-feishu",
                "school-a",
                "admin-1",
                "local_path",
                "runtime-feishu-method-probability",
                null,
                "C:/workspace/output/benchmarks/live/runtime-authored/03-feishu-method-probability",
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of());
        TeacherResourceDocumentResponse runtimeGaokao = new TeacherResourceDocumentResponse(
                "doc-runtime-gaokao",
                "school-a",
                "admin-1",
                "local_path",
                "runtime-gaokao-conic",
                null,
                "C:/workspace/output/benchmarks/live/runtime-authored/04-gaokao-conic",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of());
        TeacherResourceDocumentResponse runtimeMock = new TeacherResourceDocumentResponse(
                "doc-runtime-mock",
                "school-a",
                "admin-1",
                "local_path",
                "runtime-mock-sequence",
                null,
                "C:/workspace/output/benchmarks/live/runtime-authored/05-mock-sequence",
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of());

        assertThat(TeacherResourceLibraryResolver.effectiveLibrary(runtimeQq)).isEqualTo("qq_bundle");
        assertThat(TeacherResourceLibraryResolver.effectiveLibrary(runtimeFeishu)).isEqualTo("feishu");
        assertThat(TeacherResourceLibraryResolver.effectiveLibrary(runtimeGaokao)).isEqualTo("gaokao");
        assertThat(TeacherResourceLibraryResolver.effectiveLibrary(runtimeMock)).isEqualTo("mock_exam");
        assertThat(TeacherResourceLibraryResolver.matchesAny(runtimeQq, List.of("qq_bundle"))).isTrue();
        assertThat(TeacherResourceLibraryResolver.matchesAny(runtimeFeishu, List.of("feishu"))).isTrue();
        assertThat(TeacherResourceLibraryResolver.matchesAny(runtimeGaokao, List.of("gaokao"))).isTrue();
        assertThat(TeacherResourceLibraryResolver.matchesAny(runtimeMock, List.of("mock_exam"))).isTrue();
    }

    @Test
    void recognizesRuntimeQqBundleTitlesThatUseTheLogicalLibraryUnderscore() {
        TeacherResourceDocumentResponse runtimeQq = new TeacherResourceDocumentResponse(
                "doc-runtime-qq-underscore",
                "school-a",
                "admin-1",
                "local_path",
                "runtime-qq_bundle-library-rag-001",
                null,
                "C:/workspace/output/benchmarks/library-rag/uploaded-libraries/qq_bundle",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of());

        assertThat(TeacherResourceLibraryResolver.effectiveLibrary(runtimeQq)).isEqualTo("qq_bundle");
        assertThat(TeacherResourceLibraryResolver.matchesAny(runtimeQq, List.of("qq_bundle"))).isTrue();
    }

    @Test
    void doesNotInferTextbookLibraryFromGenericTeacherTitleOrPathAnyMore() {
        TeacherResourceDocumentResponse teacherHandout = new TeacherResourceDocumentResponse(
                "doc-teacher-textbook-wording",
                "school-a",
                "teacher-1",
                "local_path",
                "教材配套导数讲义",
                null,
                "C:/workspace/uploads/chapter-derivative-handout",
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of());

        assertThat(TeacherResourceLibraryResolver.effectiveLibrary(teacherHandout)).isEqualTo("teacher_resource");
        assertThat(TeacherResourceLibraryResolver.matchesAny(teacherHandout, List.of("textbook"))).isFalse();
        assertThat(TeacherResourceLibraryResolver.matchesAny(teacherHandout, List.of("teacher_resource"))).isTrue();
    }
}

