package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
            TeacherResourceBlockSearchAuditSink auditSink) {
        this.resourceStore = resourceStore;
        this.blockStore = blockStore;
        this.auditSink = auditSink == null ? NoopTeacherResourceBlockSearchAuditSink.INSTANCE : auditSink;
    }

    /**
     * Creates a parsed block search service without audit recording for legacy focused tests.
     */
    public TeacherResourceBlockSearchService(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore) {
        this(resourceStore, blockStore, NoopTeacherResourceBlockSearchAuditSink.INSTANCE);
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
        String normalizedTenantId = textOrDefault(tenantId, "default");
        String normalizedRole = textOrDefault(viewerRole, "anonymous").toLowerCase(Locale.ROOT);
        String normalizedSubjectId = textOrDefault(viewerSubjectId, "");
        requireTeacherOrAdmin(normalizedRole);
        String normalizedQuery = normalizeQuery(query);
        int safeLimit = clampLimit(limit);
        if (normalizedQuery.isBlank()) {
            TeacherResourceBlockSearchResponse emptyResponse = response(normalizedQuery, safeLimit, List.of());
            recordAudit(normalizedTenantId, normalizedRole, normalizedSubjectId, endpoint, emptyResponse, startedNanos);
            return emptyResponse;
        }
        String[] terms = searchTerms(normalizedQuery);
        List<TeacherResourceDocumentResponse> documents =
                resourceStore.listSearchable(normalizedTenantId, normalizedRole, normalizedSubjectId);
        List<TeacherResourceBlockSearchResponse.Hit> hits = documents.stream()
                .flatMap(document -> blockStore.listByDocument(normalizedTenantId, document.documentId()).stream()
                        .map(block -> hit(document, block, normalizedQuery, terms)))
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparingDouble(TeacherResourceBlockSearchResponse.Hit::score).reversed()
                        .thenComparing(TeacherResourceBlockSearchResponse.Hit::documentTitle)
                        .thenComparingInt(TeacherResourceBlockSearchResponse.Hit::blockOrder))
                .limit(safeLimit)
                .toList();
        TeacherResourceBlockSearchResponse searchResponse = response(normalizedQuery, safeLimit, hits);
        recordAudit(normalizedTenantId, normalizedRole, normalizedSubjectId, endpoint, searchResponse, startedNanos);
        return searchResponse;
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
            List<TeacherResourceBlockSearchResponse.Hit> hits) {
        return new TeacherResourceBlockSearchResponse(
                UUID.randomUUID().toString(),
                normalizedQuery,
                safeLimit,
                "teacher_block_lexical",
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
}
