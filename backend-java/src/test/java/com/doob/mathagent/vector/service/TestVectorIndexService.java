package com.doob.mathagent.vector.service;

import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.TeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.TeacherResourceStore;
import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Test-only vector index service that updates resource status without leaving the JVM.
 */
public final class TestVectorIndexService extends VectorIndexService {

    private final TeacherResourceStore resourceStore;
    private final TeacherDocumentBlockStore blockStore;
    private final boolean fail;

    private TestVectorIndexService(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore,
            boolean fail) {
        super(
                new VectorIndexProperties(
                        true,
                        "http://test-milvus.local:19530",
                        "test-token",
                        "math_agent_resource_blocks",
                        512,
                        "http://test-embedding.local/v1",
                        "test-embedding-key",
                        "local-clip-test",
                        1000),
                new NoOpTransport(),
                resourceStore,
                blockStore);
        this.resourceStore = resourceStore;
        this.blockStore = blockStore;
        this.fail = fail;
    }

    public static VectorIndexService successful(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore) {
        return new TestVectorIndexService(resourceStore, blockStore, false);
    }

    public static VectorIndexService failing(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore) {
        return new TestVectorIndexService(resourceStore, blockStore, true);
    }

    @Override
    public VectorIndexRebuildResponse rebuildTeacherResource(
            String tenantId,
            String subjectType,
            String subjectId,
            String documentId) {
        TeacherResourceDocumentResponse document = resourceStore.find(tenantId, documentId);
        if (document == null) {
            throw new IllegalArgumentException("Teacher resource not found: " + documentId);
        }
        List<TeacherDocumentBlockResponse> blocks = blockStore.listByDocument(tenantId, documentId);
        String status = fail ? "failed" : "indexed";
        String nextEmbeddingStatus = fail ? "failed" : "ready";
        String nextIndexStatus = fail ? "failed" : "ready";
        resourceStore.save(new TeacherResourceDocumentResponse(
                document.documentId(),
                document.tenantId(),
                document.ownerSubjectId(),
                document.sourceType(),
                document.title(),
                document.originalUrl(),
                document.localPath(),
                document.permissionScope(),
                document.syncStatus(),
                document.parseStatus(),
                nextEmbeddingStatus,
                nextIndexStatus,
                document.feishuExportFormat(),
                document.previewFiles()));
        return new VectorIndexRebuildResponse(
                status,
                documentId,
                "math_agent_resource_blocks",
                blocks.size(),
                blocks.size(),
                fail ? 0 : blocks.size(),
                "local-clip-test",
                0,
                fail ? "test vector index failure" : "test vector index indexed");
    }

    @Override
    public List<VectorSearchHit> searchTeacherResourceBlocks(String query, int limit) {
        if (!(resourceStore instanceof InMemoryTeacherResourceStore memoryStore)) {
            return List.of();
        }
        String normalizedQuery = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isBlank()) {
            return List.of();
        }
        String[] terms = normalizedQuery.split("\\s+");
        return memoryStore.snapshot().stream()
                .sorted(Comparator.comparing(TeacherResourceDocumentResponse::title)
                        .thenComparing(TeacherResourceDocumentResponse::documentId))
                .flatMap(document -> blockStore.listByDocument(document.tenantId(), document.documentId()).stream())
                .filter(block -> containsAnyTerm(block, terms))
                .limit(Math.max(1, limit))
                .map(block -> new VectorSearchHit(
                        block.documentId(),
                        block.blockId(),
                        text(block.normalizedText(), block.rawText()),
                        0.1))
                .toList();
    }

    @Override
    public int deleteTeacherResourceVectors(String tenantId, String documentId) {
        return blockStore.listByDocument(tenantId, documentId).size();
    }

    @Override
    public VectorIndexStatusResponse status() {
        return new VectorIndexStatusResponse(
                true,
                true,
                "math_agent_resource_blocks",
                512,
                "local-clip-test",
                "http://test-milvus.local:19530",
                "exists",
                "Finished",
                "LoadStateLoaded",
                0,
                fail ? "failed" : "searchable");
    }

    private static boolean containsAnyTerm(TeacherDocumentBlockResponse block, String[] terms) {
        String haystack = text(block.normalizedText(), block.rawText()).toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (!term.isBlank() && haystack.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static String text(String primary, String fallback) {
        return primary == null || primary.isBlank() ? (fallback == null ? "" : fallback) : primary;
    }

    private static final class NoOpTransport implements VectorHttpTransport {

        @Override
        public VectorHttpResponse postJson(URI uri, Map<String, String> headers, String body, Duration timeout) {
            throw new UnsupportedOperationException("TestVectorIndexService does not use HTTP transport");
        }
    }
}
