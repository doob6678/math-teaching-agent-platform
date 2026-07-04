package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import com.doob.mathagent.vector.service.VectorIndexService;
import com.doob.mathagent.vector.service.VectorSearchHit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Searches parsed teacher resource blocks with backend-controlled tenant and scope visibility.
 */
@Service
public class TeacherResourceBlockSearchService {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;
    private static final int SNIPPET_RADIUS = 80;

    private final TeacherResourceStore resourceStore;
    private final TeacherDocumentBlockStore blockStore;
    private final TeacherResourceBlockSearchAuditSink auditSink;
    private final VectorIndexService vectorIndexService;

    /**
     * Creates a parsed block search service.
     *
     * @param resourceStore source document store
     * @param blockStore parsed document block store
     * @param auditSink recent audit sink for UI and MCP queryId lookup
     */
    @Autowired
    public TeacherResourceBlockSearchService(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore,
            TeacherResourceBlockSearchAuditSink auditSink,
            VectorIndexService vectorIndexService) {
        this.resourceStore = Objects.requireNonNull(resourceStore, "resourceStore is required");
        this.blockStore = Objects.requireNonNull(blockStore, "blockStore is required");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink is required");
        this.vectorIndexService = Objects.requireNonNull(vectorIndexService, "vectorIndexService is required");
    }

    /**
     * Searches active parsed blocks visible to the backend-resolved teacher/admin subject.
     *
     * @param tenantId backend-resolved tenant id
     * @param viewerRole backend-resolved viewer role
     * @param viewerSubjectId backend-resolved subject id
     * @param query user search query
     * @param limit requested maximum hit count
     * @return visible block search response
     */
    public TeacherResourceBlockSearchResponse search(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String query,
            int limit) {
        return search(tenantId, viewerRole, viewerSubjectId, query, limit, "/api/teacher/resources/search");
    }

    /**
     * Searches visible blocks and records the logical caller endpoint for audit correlation.
     *
     * @param tenantId backend-resolved tenant id
     * @param viewerRole backend-resolved viewer role
     * @param viewerSubjectId backend-resolved subject id
     * @param query user search query
     * @param limit requested maximum hit count
     * @param endpoint logical API endpoint or MCP tool call path
     * @return visible block search response
     */
    public TeacherResourceBlockSearchResponse search(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String query,
            int limit,
            String endpoint) {
        long startedNanos = System.nanoTime();
        String normalizedTenantId = requireText(tenantId, "tenantId is required");
        String normalizedRole = requireText(viewerRole, "viewerRole is required").toLowerCase(Locale.ROOT);
        String normalizedSubjectId = requireText(viewerSubjectId, "viewerSubjectId is required");
        requireTeacherOrAdmin(normalizedRole);
        String normalizedQuery = normalizeQuery(query);
        int safeLimit = clampLimit(limit);
        if (normalizedQuery.isBlank()) {
            TeacherResourceBlockSearchResponse emptyResponse = response(
                    normalizedQuery,
                    safeLimit,
                    "teacher_block_empty",
                    List.of());
            recordAudit(normalizedTenantId, normalizedRole, normalizedSubjectId, endpoint, emptyResponse, startedNanos);
            return emptyResponse;
        }
        String[] terms = searchTerms(normalizedQuery);
        List<TeacherResourceDocumentResponse> documents =
                resourceStore.listSearchable(normalizedTenantId, normalizedRole, normalizedSubjectId);
        TeacherResourceBlockSearchResponse searchResponse = hybridResponse(
                normalizedTenantId,
                documents,
                normalizedQuery,
                terms,
                safeLimit);
        recordAudit(normalizedTenantId, normalizedRole, normalizedSubjectId, endpoint, searchResponse, startedNanos);
        return searchResponse;
    }

    private TeacherResourceBlockSearchResponse hybridResponse(
            String tenantId,
            List<TeacherResourceDocumentResponse> documents,
            String normalizedQuery,
            String[] terms,
            int safeLimit) {
        if (documents.isEmpty()) {
            return response(normalizedQuery, safeLimit, "teacher_block_no_visible_documents", List.of());
        }
        Map<String, TeacherResourceDocumentResponse> documentsById = documents.stream()
                .collect(Collectors.toMap(
                        TeacherResourceDocumentResponse::documentId,
                        Function.identity(),
                        (left, ignored) -> left,
                        LinkedHashMap::new));
        Set<String> visibleDocumentIds = documentsById.keySet();
        Map<String, TeacherDocumentBlockResponse> blocksById = new LinkedHashMap<>();
        for (TeacherResourceDocumentResponse document : documents) {
            for (TeacherDocumentBlockResponse block : blockStore.listByDocument(document.tenantId(), document.documentId())) {
                blocksById.put(block.blockId(), block);
            }
        }
        List<TeacherResourceBlockSearchResponse.Hit> vectorHits = vectorIndexService
                .searchTeacherResourceBlocks(normalizedQuery, Math.max(safeLimit * 10, 50))
                .stream()
                .filter(hit -> visibleDocumentIds.contains(hit.documentId()))
                .map(hit -> toVectorHit(documentsById.get(hit.documentId()), blocksById.get(hit.blockId()), hit, normalizedQuery, terms))
                .filter(Objects::nonNull)
                .toList();
        List<TeacherResourceBlockSearchResponse.Hit> lexicalHits = lexicalHits(
                tenantId,
                documents,
                normalizedQuery,
                terms,
                Math.max(safeLimit * 4, safeLimit));
        List<TeacherResourceBlockSearchResponse.Hit> hits = mergeHybridHits(vectorHits, lexicalHits, safeLimit);
        return response(normalizedQuery, safeLimit, "teacher_block_hybrid", hits);
    }

    private static List<TeacherResourceBlockSearchResponse.Hit> mergeHybridHits(
            List<TeacherResourceBlockSearchResponse.Hit> vectorHits,
            List<TeacherResourceBlockSearchResponse.Hit> lexicalHits,
            int safeLimit) {
        Map<String, TeacherResourceBlockSearchResponse.Hit> merged = new LinkedHashMap<>();
        for (TeacherResourceBlockSearchResponse.Hit hit : vectorHits) {
            merged.put(hit.documentId() + ":" + hit.blockId(), hit);
        }
        for (TeacherResourceBlockSearchResponse.Hit lexicalHit : lexicalHits) {
            String key = lexicalHit.documentId() + ":" + lexicalHit.blockId();
            TeacherResourceBlockSearchResponse.Hit vectorHit = merged.get(key);
            merged.put(key, vectorHit == null ? lexicalHit : boostLexicalHit(lexicalHit, vectorHit.score()));
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(TeacherResourceBlockSearchResponse.Hit::score).reversed()
                        .thenComparing(TeacherResourceBlockSearchResponse.Hit::documentTitle)
                        .thenComparingInt(TeacherResourceBlockSearchResponse.Hit::blockOrder))
                .limit(safeLimit)
                .toList();
    }

    private static TeacherResourceBlockSearchResponse.Hit boostLexicalHit(
            TeacherResourceBlockSearchResponse.Hit lexicalHit,
            double vectorScore) {
        return new TeacherResourceBlockSearchResponse.Hit(
                lexicalHit.documentId(),
                lexicalHit.documentTitle(),
                lexicalHit.permissionScope(),
                lexicalHit.blockId(),
                lexicalHit.blockType(),
                lexicalHit.blockOrder(),
                lexicalHit.chapter(),
                lexicalHit.section(),
                lexicalHit.pageNo(),
                lexicalHit.snippet(),
                lexicalHit.score() + Math.max(vectorScore, 0));
    }

    private static TeacherResourceBlockSearchResponse.Hit toVectorHit(
            TeacherResourceDocumentResponse document,
            TeacherDocumentBlockResponse block,
            VectorSearchHit vectorHit,
            String normalizedQuery,
            String[] terms) {
        if (document == null || block == null) {
            return null;
        }
        return new TeacherResourceBlockSearchResponse.Hit(
                document.documentId(),
                document.title(),
                document.permissionScope(),
                block.blockId(),
                block.blockType(),
                block.blockOrder(),
                block.chapter(),
                block.section(),
                block.pageNo(),
                snippet(textOrDefault(block.rawText(), vectorHit.text()), normalizedQuery, terms),
                vectorHit.score());
    }

    private List<TeacherResourceBlockSearchResponse.Hit> lexicalHits(
            String tenantId,
            List<TeacherResourceDocumentResponse> documents,
            String normalizedQuery,
            String[] terms,
            int safeLimit) {
        return documents.stream()
                .flatMap(document -> blockStore.listByDocument(tenantId, document.documentId()).stream()
                        .map(block -> hit(document, block, normalizedQuery, terms)))
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparingDouble(TeacherResourceBlockSearchResponse.Hit::score).reversed()
                        .thenComparing(TeacherResourceBlockSearchResponse.Hit::documentTitle)
                        .thenComparingInt(TeacherResourceBlockSearchResponse.Hit::blockOrder))
                .limit(safeLimit)
                .toList();
    }

    /**
     * Records the search audit event without letting audit failures break read-only search.
     */
    private void recordAudit(
            String tenantId,
            String subjectType,
            String subjectId,
            String endpoint,
            TeacherResourceBlockSearchResponse response,
            long startedNanos) {
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        auditSink.record(TeacherResourceBlockSearchAuditEvent.from(
                tenantId,
                subjectType,
                subjectId,
                textOrDefault(endpoint, "/api/teacher/resources/search"),
                response,
                elapsedMs));
    }

    /**
     * Builds a hit with a deterministic lexical score.
     */
    private static TeacherResourceBlockSearchResponse.Hit hit(
            TeacherResourceDocumentResponse document,
            TeacherDocumentBlockResponse block,
            String normalizedQuery,
            String[] terms) {
        String searchableText = normalizeText(textOrDefault(block.normalizedText(), block.rawText()));
        double score = score(searchableText, normalizedQuery, terms);
        return new TeacherResourceBlockSearchResponse.Hit(
                document.documentId(),
                document.title(),
                document.permissionScope(),
                block.blockId(),
                block.blockType(),
                block.blockOrder(),
                block.chapter(),
                block.section(),
                block.pageNo(),
                snippet(textOrDefault(block.rawText(), block.normalizedText()), normalizedQuery, terms),
                score);
    }

    /**
     * Scores exact query and term matches without claiming vector semantics.
     */
    private static double score(String searchableText, String normalizedQuery, String[] terms) {
        if (searchableText.isBlank()) {
            return 0;
        }
        double score = searchableText.contains(normalizedQuery) ? 10 : 0;
        for (String term : terms) {
            if (!term.isBlank() && searchableText.contains(term)) {
                score += 1;
            }
        }
        return score;
    }

    /**
     * Builds a compact match snippet around the first exact or term match.
     */
    private static String snippet(String rawText, String normalizedQuery, String[] terms) {
        String text = textOrDefault(rawText, "");
        if (text.isBlank()) {
            return "";
        }
        String lower = normalizeText(text);
        int matchIndex = lower.indexOf(normalizedQuery);
        if (matchIndex < 0) {
            matchIndex = Arrays.stream(terms)
                    .filter(term -> !term.isBlank())
                    .mapToInt(lower::indexOf)
                    .filter(index -> index >= 0)
                    .findFirst()
                    .orElse(0);
        }
        int start = Math.max(0, matchIndex - SNIPPET_RADIUS);
        int end = Math.min(text.length(), matchIndex + normalizedQuery.length() + SNIPPET_RADIUS);
        String prefix = start > 0 ? "..." : "";
        String suffix = end < text.length() ? "..." : "";
        return prefix + text.substring(start, end).strip() + suffix;
    }

    /**
     * Builds the response envelope.
     */
    private static TeacherResourceBlockSearchResponse response(
            String normalizedQuery,
            int safeLimit,
            String retrievalMode,
            List<TeacherResourceBlockSearchResponse.Hit> hits) {
        return new TeacherResourceBlockSearchResponse(
                UUID.randomUUID().toString(),
                normalizedQuery,
                safeLimit,
                retrievalMode,
                hits.size(),
                hits);
    }

    /**
     * Ensures only teacher/admin backend subjects can use this teacher resource endpoint.
     */
    private static void requireTeacherOrAdmin(String viewerRole) {
        if (!"teacher".equals(viewerRole) && !"admin".equals(viewerRole)) {
            throw new IllegalArgumentException("Teacher resource block search requires teacher or admin role");
        }
    }

    /**
     * Clamps query result size to keep the read path bounded.
     */
    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * Normalizes a query for lexical matching.
     */
    private static String normalizeQuery(String query) {
        return normalizeText(textOrDefault(query, ""));
    }

    /**
     * Splits a normalized query into non-empty terms.
     */
    private static String[] searchTerms(String normalizedQuery) {
        return Arrays.stream(normalizedQuery.split("\\s+"))
                .filter(term -> !term.isBlank())
                .toArray(String[]::new);
    }

    /**
     * Normalizes searchable text by case folding and whitespace compaction.
     */
    private static String normalizeText(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    /**
     * Returns stripped text or a fallback when blank.
     */
    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    /**
     * Returns stripped text or fails when backend identity is missing.
     */
    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
