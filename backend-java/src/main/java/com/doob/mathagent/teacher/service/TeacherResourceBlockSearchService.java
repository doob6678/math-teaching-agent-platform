package com.doob.mathagent.teacher.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.resources.TextbookResourceProperties;
import com.doob.mathagent.retrieval.RetrievalRequestContext;
import com.doob.mathagent.retrieval.TextbookPageImageSearchHit;
import com.doob.mathagent.retrieval.TextbookPageImageSearchRequest;
import com.doob.mathagent.retrieval.TextbookPageImageSearchResponse;
import com.doob.mathagent.retrieval.TextbookPageImageSearchService;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.retrieval.TextbookSearchHit;
import com.doob.mathagent.retrieval.TextbookSearchRequest;
import com.doob.mathagent.retrieval.TextbookSearchResponse;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockStore;
import com.doob.mathagent.teacher.document.TeacherResourceStore;
import com.doob.mathagent.teacher.search.TeacherResourceGraphAlignmentService;
import com.doob.mathagent.teacher.search.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.search.TeacherResourceSearchFilter;
import com.doob.mathagent.teacher.search.audit.TeacherResourceBlockSearchAuditEvent;
import com.doob.mathagent.teacher.search.audit.TeacherResourceBlockSearchAuditSink;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.support.TeacherResourceLibraryResolver;
import com.doob.mathagent.vector.service.VectorIndexService;
import com.doob.mathagent.vector.service.VectorSearchFilter;
import com.doob.mathagent.vector.service.VectorSearchHit;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Searches parsed teacher resource blocks with backend-controlled tenant and scope visibility.
 *
 * <p>The default path is an explicit two-stage retriever:</p>
 *
 * <ol>
 *     <li>Aggregate vector + lexical evidence at document level so the right document is more likely to enter the
 *     candidate set even when several sibling blocks look similar.</li>
 *     <li>Rerank blocks only inside those candidate documents using block role, headings, source path, graph tags,
 *     and neighboring evidence windows.</li>
 * </ol>
 *
 * <p>Production now exposes only one retrieval implementation so document recall and in-document block rerank stay
 * single-sourced across teacher search, agent tools, and textbook merge paths.</p>
 */
@Service
public class TeacherResourceBlockSearchService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(TeacherResourceBlockSearchService.class);
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;
    private static final int MAX_RETRIEVAL_MODE_LENGTH = 64;
    private static final int SNIPPET_RADIUS = 80;
    private static final int EVIDENCE_WINDOW_RADIUS = 1;
    private static final int MAX_SEARCH_TERMS = 32;
    private static final int MAX_DOCUMENT_RERANK_BLOCKS_PER_DOCUMENT = 3;
    private static final int MAX_BLOCK_RERANK_BLOCKS_PER_DOCUMENT = 2;
    private static final int MAX_RERANK_TITLE_CHARS = 120;
    private static final int MAX_RERANK_ROLE_CHARS = 48;
    private static final int MAX_RERANK_HEADING_CHARS = 120;
    private static final int MAX_RERANK_SOURCE_PATH_CHARS = 220;
    private static final int MAX_RERANK_GRAPH_TAGS_CHARS = 180;
    private static final int MAX_RERANK_IMAGE_REFS_CHARS = 180;
    private static final int MAX_RERANK_EVIDENCE_BLOCK_IDS_CHARS = 120;
    private static final int MAX_DOCUMENT_RERANK_EVIDENCE_CHARS = 520;
    private static final int MAX_BLOCK_RERANK_EVIDENCE_CHARS = 900;
    private static final int MAX_MERGE_RERANK_EVIDENCE_CHARS = 760;
    private static final String STRATEGY_TWO_STAGE_DOC_BLOCK = "two_stage_doc_block";

    private final TeacherResourceStore resourceStore;
    private final TeacherDocumentBlockStore blockStore;
    private final TeacherResourceBlockSearchAuditSink auditSink;
    private final VectorIndexService vectorIndexService;
    private final TeacherResourceGraphAlignmentService graphAlignmentService;
    private final TeacherResourceAssetService assetService;
    private final TextbookRetrievalService textbookRetrievalService;
    private final TextbookPageImageSearchService textbookPageImageSearchService;
    private final TextbookResourceProperties textbookResourceProperties;

    /**
     * Creates a parsed block search service.
     *
     * @param resourceStore source document store
     * @param blockStore parsed document block store
     * @param auditSink recent audit sink for UI and MCP queryId lookup
     */
    public TeacherResourceBlockSearchService(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore,
            TeacherResourceBlockSearchAuditSink auditSink,
            VectorIndexService vectorIndexService) {
        this(
                resourceStore,
                blockStore,
                auditSink,
                vectorIndexService,
                TeacherResourceGraphAlignmentService.disabled(),
                TeacherResourceAssetService.disabled(),
                null,
                null,
                null);
    }

    /**
     * Production constructor with graph-aware query normalization.
     */
    @Autowired
    public TeacherResourceBlockSearchService(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore,
            TeacherResourceBlockSearchAuditSink auditSink,
            VectorIndexService vectorIndexService,
            TeacherResourceGraphAlignmentService graphAlignmentService,
            TeacherResourceAssetService assetService,
            TextbookRetrievalService textbookRetrievalService,
            TextbookPageImageSearchService textbookPageImageSearchService,
            TextbookResourceProperties textbookResourceProperties) {
        this.resourceStore = Objects.requireNonNull(resourceStore, "resourceStore is required");
        this.blockStore = Objects.requireNonNull(blockStore, "blockStore is required");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink is required");
        this.vectorIndexService = Objects.requireNonNull(vectorIndexService, "vectorIndexService is required");
        this.graphAlignmentService = Objects.requireNonNull(graphAlignmentService, "graphAlignmentService is required");
        this.assetService = Objects.requireNonNull(assetService, "assetService is required");
        this.textbookRetrievalService = textbookRetrievalService;
        this.textbookPageImageSearchService = textbookPageImageSearchService;
        this.textbookResourceProperties = textbookResourceProperties;
    }

    public TeacherResourceBlockSearchService(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore,
            TeacherResourceBlockSearchAuditSink auditSink,
            VectorIndexService vectorIndexService,
            TeacherResourceGraphAlignmentService graphAlignmentService,
            TeacherResourceAssetService assetService) {
        this(
                resourceStore,
                blockStore,
                auditSink,
                vectorIndexService,
                graphAlignmentService,
                assetService,
                null,
                null,
                null);
    }

    /**
     * Backward-compatible constructor kept for focused tests that only provide graph alignment.
     */
    public TeacherResourceBlockSearchService(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore,
            TeacherResourceBlockSearchAuditSink auditSink,
            VectorIndexService vectorIndexService,
            TeacherResourceGraphAlignmentService graphAlignmentService) {
        this(
                resourceStore,
                blockStore,
                auditSink,
                vectorIndexService,
                graphAlignmentService,
                TeacherResourceAssetService.disabled(),
                null,
                null,
                null);
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
        return search(
                tenantId,
                viewerRole,
                viewerSubjectId,
                query,
                limit,
                endpoint,
                TeacherResourceSearchFilter.EMPTY);
    }

    /**
     * Searches visible blocks and applies optional metadata filters.
     */
    public TeacherResourceBlockSearchResponse search(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String query,
            int limit,
            String endpoint,
            TeacherResourceSearchFilter filter) {
        long startedNanos = System.nanoTime();
        String normalizedTenantId = requireText(tenantId, "tenantId is required");
        String normalizedRole = requireText(viewerRole, "viewerRole is required").toLowerCase(Locale.ROOT);
        String normalizedSubjectId = requireText(viewerSubjectId, "viewerSubjectId is required");
        requireTeacherOrAdmin(normalizedRole);
        String normalizedQuery = normalizeQuery(query);
        int safeLimit = clampLimit(limit);
        TeacherResourceSearchFilter normalizedFilter = filter == null ? TeacherResourceSearchFilter.EMPTY : filter;
        if (normalizedQuery.isBlank()) {
            TeacherResourceBlockSearchResponse emptyResponse = response(
                    normalizedQuery,
                    safeLimit,
                    STRATEGY_TWO_STAGE_DOC_BLOCK + "_empty",
                    List.of());
            recordAudit(normalizedTenantId, normalizedRole, normalizedSubjectId, endpoint, emptyResponse, startedNanos);
            return emptyResponse;
        }
        String[] terms = searchTerms(normalizedQuery);
        TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph = graphAlignmentService.alignQuery(
                normalizedTenantId,
                normalizedRole,
                normalizedSubjectId,
                normalizedQuery);
        boolean includeRealTextbook = shouldUseRealTextbook(normalizedFilter);
        TeacherResourceBlockSearchResponse searchResponse;
        if (includeRealTextbook && isTextbookOnlyFilter(normalizedFilter)) {
            /*
             * When the caller explicitly pins the library to textbook, searching teacher-resource rows first only
             * adds generic local_path noise and wastes rerank budget on a corpus that can never satisfy the filter.
             * Go straight to the dedicated textbook retriever, then keep the normal merge/output path below.
             */
            searchResponse = response(
                    normalizedQuery,
                    safeLimit,
                    retrievalMode(STRATEGY_TWO_STAGE_DOC_BLOCK, normalizedFilter, "textbook_only"),
                    List.of());
        } else {
            List<TeacherResourceDocumentResponse> documents =
                    filteredDocuments(
                            resourceStore.listSearchable(normalizedTenantId, normalizedRole, normalizedSubjectId),
                            normalizedFilter);
            if (includeRealTextbook) {
                /*
                 * Once processed_books is available, teacher search must not keep a second stale textbook branch in
                 * source_document/document_block. Those imported PUBLIC_TEXTBOOK rows were only a historical bridge before
                 * the dedicated textbook retriever and page-image index existed. Keeping both sources in mixed mode lets
                 * old derivative rows compete against the real textbook page hit and makes "no library specified" behave
                 * differently from "library=textbook".
                 *
                 * We therefore delete teacher-store textbook derivatives whenever the real textbook retriever is allowed
                 * for this request, even when the caller is doing a mixed multi-library search.
                 */
                documents = documents.stream()
                        .filter(document -> !"public_textbook".equals(TeacherResourceLibraryResolver.effectiveLibrary(document)))
                        .toList();
            }
            searchResponse = twoStageResponse(
                    normalizedTenantId,
                    documents,
                    normalizedQuery,
                    terms,
                    safeLimit,
                    normalizedFilter,
                    queryGraph);
        }
        if (includeRealTextbook) {
            searchResponse = mergeRealTextbookHits(
                    searchResponse,
                    normalizedTenantId,
                    normalizedRole,
                    normalizedSubjectId,
                    normalizedQuery,
                    safeLimit,
                    endpoint);
        }
        searchResponse = attachVisibleAssetRefs(
                searchResponse,
                new RequestSubject(normalizedTenantId, normalizedRole, normalizedSubjectId, null));
        recordAudit(normalizedTenantId, normalizedRole, normalizedSubjectId, endpoint, searchResponse, startedNanos);
        return searchResponse;
    }

    private TeacherResourceBlockSearchResponse twoStageResponse(
            String tenantId,
            List<TeacherResourceDocumentResponse> documents,
            String normalizedQuery,
            String[] terms,
            int safeLimit,
            TeacherResourceSearchFilter filter,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
        if (documents.isEmpty()) {
            return response(normalizedQuery, safeLimit, retrievalMode(STRATEGY_TWO_STAGE_DOC_BLOCK, filter, "no_visible_documents"), List.of());
        }
        Map<String, TeacherResourceDocumentResponse> documentsById = documents.stream()
                .collect(Collectors.toMap(
                        TeacherResourceDocumentResponse::documentId,
                        Function.identity(),
                        (left, ignored) -> left,
                        LinkedHashMap::new));
        Map<String, List<BlockContext>> blocksByDocumentId = loadVisibleBlockContexts(tenantId, documents, filter.tags());
        if (blocksByDocumentId.isEmpty()) {
            return response(normalizedQuery, safeLimit, retrievalMode(STRATEGY_TWO_STAGE_DOC_BLOCK, filter, "no_visible_blocks"), List.of());
        }
        Map<String, Double> vectorScoreByKey = vectorScoreByKey(
                normalizedQuery,
                safeLimit,
                blocksByDocumentId,
                filter);
        List<DocumentCandidate> documentCandidates = rerankedDocumentCandidates(
                documentsById,
                blocksByDocumentId,
                vectorScoreByKey,
                normalizedQuery,
                terms,
                safeLimit,
                queryGraph);
        List<DocumentCandidate> rankedDocuments = documentCandidates.stream()
                .sorted(documentCandidateComparator()
                        .thenComparing(candidate -> candidate.document().title())
                        .thenComparing(candidate -> candidate.document().documentId()))
                .limit(candidateDocumentLimit(safeLimit, documentCandidates.size()))
                .toList();
        List<TeacherResourceBlockSearchResponse.Hit> hits = rerankedBlockHits(
                rankedDocuments,
                blocksByDocumentId,
                vectorScoreByKey,
                normalizedQuery,
                terms,
                safeLimit,
                filter,
                queryGraph);
        return response(normalizedQuery, safeLimit, retrievalMode(STRATEGY_TWO_STAGE_DOC_BLOCK, filter, null), hits);
    }

    /**
     * Merges real textbook hits into the teacher-facing response shape. Mixed queries also need this path because the
     * teacher store no longer carries textbook derivative rows once processed_books is available; otherwise a search
     * without `library=textbook` would silently lose textbook recall.
     */
    private TeacherResourceBlockSearchResponse mergeRealTextbookHits(
            TeacherResourceBlockSearchResponse teacherResponse,
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String normalizedQuery,
            int safeLimit,
            String endpoint) {
        if (textbookRetrievalService == null || textbookResourceProperties == null) {
            return teacherResponse;
        }
        TextbookSearchResponse textbookResponse = textbookRetrievalService.search(
                textbookResourceProperties.processedBooksRoot(),
                new TextbookSearchRequest(normalizedQuery, safeLimit),
                new RetrievalRequestContext(
                        tenantId,
                        viewerRole,
                        viewerSubjectId,
                        null,
                        null,
                        "teacher-resource-search",
                        endpoint));
        List<TeacherResourceBlockSearchResponse.Hit> textbookTextHits = textbookResponse.hits().stream()
                .map(TeacherResourceBlockSearchService::textbookHit)
                .toList();
        List<String> candidateDocIds = textbookResponse.hits().stream()
                .map(TextbookSearchHit::docId)
                .filter(docId -> docId != null && !docId.isBlank())
                .distinct()
                .toList();
        /*
         * CLIP page search is a multimodal fallback, not the default path for every text query. When textbook text
         * hits already exist, forcing page-image retrieval and a second cross-source rerank adds large latency while
         * rarely changing the winner. Keep the image path for the real failure mode: text recall missed but the page
         * image itself may still be distinctive.
         */
        List<TeacherResourceBlockSearchResponse.Hit> textbookImageHits = textbookTextHits.isEmpty()
                ? textbookImageHits(normalizedQuery, safeLimit, candidateDocIds)
                : List.of();
        boolean teacherHitsEmpty = teacherResponse == null || teacherResponse.hits() == null || teacherResponse.hits().isEmpty();
        if (teacherHitsEmpty && textbookImageHits.isEmpty() && !textbookTextHits.isEmpty()) {
            return new TeacherResourceBlockSearchResponse(
                    teacherResponse == null ? UUID.randomUUID().toString() : teacherResponse.queryId(),
                    normalizedQuery,
                    safeLimit,
                    safeRetrievalMode(buildTextbookMergeMode(teacherResponse, textbookResponse, false) + "_text_only"),
                    textbookTextHits.size(),
                    textbookTextHits.stream().limit(safeLimit).toList());
        }
        List<TeacherResourceBlockSearchResponse.Hit> combinedHits = new ArrayList<>();
        combinedHits.addAll(textbookTextHits);
        combinedHits.addAll(textbookImageHits);
        if (teacherResponse != null && teacherResponse.hits() != null) {
            combinedHits.addAll(teacherResponse.hits());
        }
        List<TeacherResourceBlockSearchResponse.Hit> mergedHits = semanticMergeHits(normalizedQuery, combinedHits, safeLimit);
        if (mergedHits.isEmpty()) {
            return teacherResponse;
        }
        return new TeacherResourceBlockSearchResponse(
                teacherResponse == null ? UUID.randomUUID().toString() : teacherResponse.queryId(),
                normalizedQuery,
                safeLimit,
                safeRetrievalMode(buildTextbookMergeMode(teacherResponse, textbookResponse, !textbookImageHits.isEmpty())),
                mergedHits.size(),
                mergedHits);
    }

    /**
     * Textbook search now has two real evidence routes: processed_books text chunks and the worker-maintained CLIP page
     * index. We merge both before the final teacher-search ranking so text-heavy pages and image-distinct pages can
     * compete in the same semantic space instead of relying on hand-written cross-source score scaling.
     */
    private List<TeacherResourceBlockSearchResponse.Hit> textbookImageHits(
            String normalizedQuery,
            int safeLimit,
            List<String> candidateDocIds) {
        if (textbookPageImageSearchService == null) {
            return List.of();
        }
        try {
            TextbookPageImageSearchResponse response = textbookPageImageSearchService.search(
                    new TextbookPageImageSearchRequest(normalizedQuery, null, safeLimit, candidateDocIds == null ? List.of() : candidateDocIds));
            return response.hits().stream()
                    .map(TeacherResourceBlockSearchService::textbookImageHit)
                    .toList();
        } catch (RuntimeException exception) {
            log.warn("teacher_resource_search_textbook_image_fallback query={} message={}",
                    normalizedQuery,
                    textOrDefault(exception.getMessage(), ""),
                    exception);
            return List.of();
        }
    }

    private static String buildTextbookMergeMode(
            TeacherResourceBlockSearchResponse teacherResponse,
            TextbookSearchResponse textbookResponse,
            boolean usedImageRoute) {
        /*
         * This value is persisted into teacher_resource_search_audit.retrieval_mode (VARCHAR(64)).
         * Keep it stable and short: the mode is only a diagnostic label, not a place to serialize
         * every internal stage name. If we concatenate upstream mode names here, live textbook
         * queries can start failing with audit insert errors even though retrieval itself succeeded.
         */
        StringBuilder mode = new StringBuilder("two_stage_doc_block_textbook");
        if (usedImageRoute) {
            mode.append("_clip");
        }
        if (textbookResponse != null
                && textbookResponse.retrievalStrategy() != null
                && textbookResponse.retrievalStrategy().contains("page")) {
            mode.append("_page");
        }
        mode.append("_semantic");
        return mode.toString();
    }

    /**
     * Loads active blocks once and keeps normalized metadata alongside them so stage one and stage two share the same
     * real parsed source state.
     */
    private Map<String, List<BlockContext>> loadVisibleBlockContexts(
            String tenantId,
            List<TeacherResourceDocumentResponse> documents,
            List<String> tags) {
        Map<String, List<BlockContext>> contexts = new LinkedHashMap<>();
        for (TeacherResourceDocumentResponse document : documents) {
            List<BlockContext> blocks = blockStore.listByDocument(tenantId, document.documentId()).stream()
                    .filter(block -> matchesTags(document, block, tags))
                    .map(block -> toContext(document, block))
                    .toList();
            if (!blocks.isEmpty()) {
                contexts.put(document.documentId(), blocks);
            }
        }
        return contexts;
    }

    /**
     * Stage one groups vector hits by document instead of consuming them directly as final block hits. This is the
     * main behavioral change: vector search helps decide which document is plausible, then stage two resolves the
     * correct block inside that document.
     */
    private Map<String, Double> vectorScoreByKey(
            String normalizedQuery,
            int safeLimit,
            Map<String, List<BlockContext>> blocksByDocumentId,
            TeacherResourceSearchFilter filter) {
        Set<String> visibleDocumentIds = blocksByDocumentId.keySet();
        if (visibleDocumentIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> blockIdsByDocument = new LinkedHashMap<>();
        for (Map.Entry<String, List<BlockContext>> entry : blocksByDocumentId.entrySet()) {
            blockIdsByDocument.put(
                    entry.getKey(),
                    entry.getValue().stream().map(context -> context.block().blockId()).collect(Collectors.toSet()));
        }
        /*
         * Stage one only needs enough vector candidates to let the final top-N documents surface. Asking Milvus for
         * every visible block reintroduces whole-library noise and makes long teacher folders slower for no gain.
         * Keep this bound derived from caller intent: top-N docs with top-N support blocks each.
         */
        int vectorCandidateLimit = Math.max(
                safeLimit,
                safeLimit * Math.max(1, candidateDocumentLimit(safeLimit, visibleDocumentIds.size())));
        Map<String, Double> scores = new LinkedHashMap<>();
        List<VectorSearchHit> hits;
        try {
            hits = vectorIndexService.searchTeacherResourceBlocks(
                    normalizedQuery,
                    vectorCandidateLimit,
                    new VectorSearchFilter(List.copyOf(visibleDocumentIds), filter.permissionScopes()));
        } catch (RuntimeException exception) {
            log.warn("teacher_resource_search_vector_fallback strategy=two_stage query={} message={}",
                    normalizedQuery,
                    textOrDefault(exception.getMessage(), ""),
                    exception);
            return Map.of();
        }
        for (VectorSearchHit hit : hits) {
            Set<String> visibleBlockIds = blockIdsByDocument.get(hit.documentId());
            if (visibleBlockIds == null || !visibleBlockIds.contains(hit.blockId())) {
                continue;
            }
            String key = blockKey(hit.documentId(), hit.blockId());
            scores.merge(key, hit.score(), Math::max);
        }
        return scores;
    }

    private List<DocumentCandidate> rerankedDocumentCandidates(
            Map<String, TeacherResourceDocumentResponse> documentsById,
            Map<String, List<BlockContext>> blocksByDocumentId,
            Map<String, Double> vectorScoreByKey,
            String normalizedQuery,
            String[] terms,
            int safeLimit,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
        List<DocumentCandidate> coarseCandidates = coarseDocumentCandidates(
                documentsById,
                blocksByDocumentId,
                vectorScoreByKey,
                normalizedQuery,
                terms,
                safeLimit,
                queryGraph);
        if (coarseCandidates.isEmpty()) {
            return coarseCandidates;
        }
        Map<String, Double> rerankScoreByDocumentId = documentRerankScoreById(
                coarseCandidates,
                blocksByDocumentId,
                normalizedQuery);
        return coarseCandidates.stream()
                .map(candidate -> candidate.withSemanticScore(
                        rerankScoreByDocumentId.getOrDefault(candidate.document().documentId(), candidate.semanticScore())))
                .sorted(documentCandidateComparator()
                        .thenComparing(candidate -> candidate.document().title())
                        .thenComparing(candidate -> candidate.document().documentId()))
                .limit(candidateDocumentLimit(safeLimit, coarseCandidates.size()))
                .toList();
    }

    private List<DocumentCandidate> coarseDocumentCandidates(
            Map<String, TeacherResourceDocumentResponse> documentsById,
            Map<String, List<BlockContext>> blocksByDocumentId,
            Map<String, Double> vectorScoreByKey,
            String normalizedQuery,
            String[] terms,
            int safeLimit,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
        List<DocumentCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, List<BlockContext>> entry : blocksByDocumentId.entrySet()) {
            TeacherResourceDocumentResponse document = documentsById.get(entry.getKey());
            if (document == null) {
                continue;
            }
            List<BlockContext> blocks = entry.getValue();
            List<BlockContext> supportedBlocks = supportedBlocks(
                    document,
                    blocks,
                    vectorScoreByKey,
                    normalizedQuery,
                    terms,
                    safeLimit,
                    queryGraph);
            double bestSemantic = 0.0d;
            double bestVector = 0.0d;
            int bestLexicalMatches = 0;
            int bestGraphMatches = 0;
            for (BlockContext block : supportedBlocks) {
                String key = blockKey(document.documentId(), block.block().blockId());
                double semantic = vectorScoreByKey.getOrDefault(key, 0.0d);
                bestVector = Math.max(bestVector, semantic);
                int lexicalMatches = blockLexicalMatchCount(document, block, normalizedQuery, terms);
                int graphMatches = graphAlignmentMatchCount(block.graphTags(), block.graphNodeIds(), queryGraph, normalizedQuery, terms);
                bestSemantic = Math.max(bestSemantic, semantic);
                bestLexicalMatches = Math.max(bestLexicalMatches, lexicalMatches);
                bestGraphMatches = Math.max(bestGraphMatches, graphMatches);
            }
            if (supportedBlocks.isEmpty()) {
                continue;
            }
            candidates.add(new DocumentCandidate(
                    document,
                    supportedBlocks,
                    bestSemantic,
                    bestVector,
                    bestLexicalMatches,
                    bestGraphMatches));
        }
        return candidates.stream()
                .sorted(documentCandidateComparator()
                        .thenComparing(candidate -> candidate.document().title())
                        .thenComparing(candidate -> candidate.document().documentId()))
                .limit(candidateDocumentLimit(safeLimit, candidates.size()))
                .toList();
    }

    /**
     * Two-stage rerank does not need every block from a long document. We keep only the strongest block-level support
     * signals per document, bounded by the caller's requested limit, so the real rerank model spends capacity on the
     * most plausible evidence instead of timing out on hundreds of weak siblings from the same file.
     */
    private static List<BlockContext> supportedBlocks(
            TeacherResourceDocumentResponse document,
            List<BlockContext> blocks,
            Map<String, Double> vectorScoreByKey,
            String normalizedQuery,
            String[] terms,
            int safeLimit,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        return blocks.stream()
                .filter(block -> {
                    String key = blockKey(document.documentId(), block.block().blockId());
                    double semantic = vectorScoreByKey.getOrDefault(key, 0.0d);
                    int lexicalMatches = blockLexicalMatchCount(document, block, normalizedQuery, terms);
                    int graphMatches = graphAlignmentMatchCount(block.graphTags(), block.graphNodeIds(), queryGraph, normalizedQuery, terms);
                    return semantic > 0.0d || lexicalMatches > 0 || graphMatches > 0;
                })
                .sorted(Comparator.<BlockContext>comparingDouble(
                                block -> vectorScoreByKey.getOrDefault(blockKey(document.documentId(), block.block().blockId()), 0.0d))
                        .reversed()
                        .thenComparing(Comparator.comparingInt(
                                (BlockContext block) -> blockLexicalMatchCount(document, block, normalizedQuery, terms)).reversed())
                        .thenComparing(Comparator.comparingInt(
                                (BlockContext block) -> graphAlignmentMatchCount(block.graphTags(), block.graphNodeIds(), queryGraph, normalizedQuery, terms)).reversed())
                        .thenComparing((BlockContext block) -> block.block().blockOrder()))
                .limit(Math.max(1, safeLimit))
                .toList();
    }

    /**
     * Stage one still uses vector coarse recall to decide which documents are plausible, then reranks only those
     * candidate documents with a real rerank model.
     *
     * <p>Do not collapse the whole document into one long concatenated prompt here. When several runtime-authored
     * teacher documents share a similar markdown skeleton, document-wide concatenation dilutes the exact block that
     * actually answers the query and lets unrelated but stylistically similar documents steal rank. Instead, we reuse
     * the same block semantic view used by stage two, score every candidate block inside the candidate documents once,
     * and aggregate the strongest block score back to its document. That keeps stage one document-focused while making
     * "does this document contain the right evidence somewhere inside it" the decisive semantic question.</p>
     */
    private Map<String, Double> documentRerankScoreById(
            List<DocumentCandidate> candidates,
            Map<String, List<BlockContext>> blocksByDocumentId,
            String normalizedQuery) {
        List<String> candidateKeys = new ArrayList<>();
        List<String> candidateTexts = new ArrayList<>();
        for (DocumentCandidate candidate : candidates) {
            List<BlockContext> documentBlocks = blocksByDocumentId.getOrDefault(candidate.document().documentId(), candidate.blocks());
            for (BlockContext block : candidate.blocks().stream().limit(MAX_DOCUMENT_RERANK_BLOCKS_PER_DOCUMENT).toList()) {
                candidateKeys.add(candidate.document().documentId());
                candidateTexts.add(semanticCandidateText(
                        candidate.document(),
                        block,
                        evidenceWindow(block, documentBlocks),
                        MAX_DOCUMENT_RERANK_EVIDENCE_CHARS));
            }
        }
        if (candidateTexts.isEmpty()) {
            return Map.of();
        }
        try {
            List<Double> scores = vectorIndexService.rerankTexts(normalizedQuery, candidateTexts);
            Map<String, Double> scoreById = new LinkedHashMap<>();
            for (int index = 0; index < candidateKeys.size() && index < scores.size(); index += 1) {
                scoreById.merge(candidateKeys.get(index), scores.get(index), Math::max);
            }
            return Map.copyOf(scoreById);
        } catch (RuntimeException exception) {
            log.warn("teacher_resource_search_document_rerank_fallback query={} message={}",
                    normalizedQuery,
                    textOrDefault(exception.getMessage(), ""),
                    exception);
            return Map.of();
        }
    }

    /**
     * Stage two reranks only inside candidate documents. This avoids the old failure mode where one near-duplicate
     * sibling block from an unrelated document beats the correct local neighbor block from the right document.
     */
    private List<TeacherResourceBlockSearchResponse.Hit> rerankedBlockHits(
            List<DocumentCandidate> rankedDocuments,
            Map<String, List<BlockContext>> blocksByDocumentId,
            Map<String, Double> vectorScoreByKey,
            String normalizedQuery,
            String[] terms,
            int safeLimit,
            TeacherResourceSearchFilter filter,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
        Map<String, Double> semanticScoreByKey = semanticScoreByKey(
                rankedDocuments,
                blocksByDocumentId,
                normalizedQuery);
        List<BlockCandidate> blockCandidates = new ArrayList<>();
        for (DocumentCandidate candidate : rankedDocuments) {
            List<BlockContext> documentBlocks = blocksByDocumentId.getOrDefault(candidate.document().documentId(), List.of());
            for (BlockContext block : candidate.blocks()) {
                String key = blockKey(candidate.document().documentId(), block.block().blockId());
                double semantic = semanticScoreByKey.getOrDefault(
                        key,
                        vectorScoreByKey.getOrDefault(key, 0.0d));
                int lexicalMatches = blockLexicalMatchCount(candidate.document(), block, normalizedQuery, terms);
                int graphMatches = graphAlignmentMatchCount(block.graphTags(), block.graphNodeIds(), queryGraph, normalizedQuery, terms);
                blockCandidates.add(new BlockCandidate(
                        candidate.document(),
                        block,
                        semantic,
                        lexicalMatches,
                        candidate.semanticScore(),
                        graphMatches,
                        vectorScoreByKey.getOrDefault(key, 0.0d)));
            }
        }
        List<BlockCandidate> rankedCandidates = blockCandidates.stream()
                .sorted(blockCandidateComparator()
                        .thenComparing(candidate -> candidate.document().title())
                        .thenComparing(candidate -> candidate.block().block().blockOrder()))
                .toList();
        return rankedCandidates.stream()
                .limit(safeLimit)
                .map(candidate -> toTwoStageHit(candidate, blocksByDocumentId.get(candidate.document().documentId()), normalizedQuery, terms))
                .toList();
    }

    private static int candidateDocumentLimit(int safeLimit, int visibleDocumentCount) {
        return Math.max(0, Math.min(safeLimit, visibleDocumentCount));
    }

    /**
     * Stage-one document ordering is semantic-first. Lexical, graph, and role signals are only tie-breakers so
     * retrieval no longer depends on opaque weighted score cocktails.
     */
    private static Comparator<DocumentCandidate> documentCandidateComparator() {
        Comparator<DocumentCandidate> comparator = Comparator.comparingDouble(DocumentCandidate::semanticScore).reversed();
        comparator = comparator.thenComparing(Comparator.comparingDouble(DocumentCandidate::vectorScore).reversed());
        comparator = comparator.thenComparing(Comparator.comparingInt(DocumentCandidate::lexicalMatches).reversed());
        return comparator.thenComparing(Comparator.comparingInt(DocumentCandidate::graphMatches).reversed());
    }

    /**
     * Stage two reranks candidate blocks with a dedicated rerank model when the worker provides one, then falls back
     * to embedding cosine similarity inside {@link VectorIndexService}. The candidate text carries title/chapter/
     * section/role context so the rerank can decide between near-duplicate sibling blocks inside the same document.
     */
    private Map<String, Double> semanticScoreByKey(
            List<DocumentCandidate> rankedDocuments,
            Map<String, List<BlockContext>> blocksByDocumentId,
            String normalizedQuery) {
        LinkedHashMap<String, String> candidateTexts = new LinkedHashMap<>();
        for (DocumentCandidate candidate : rankedDocuments) {
            List<BlockContext> documentBlocks = blocksByDocumentId.getOrDefault(candidate.document().documentId(), candidate.blocks());
            for (BlockContext block : candidate.blocks().stream().limit(MAX_BLOCK_RERANK_BLOCKS_PER_DOCUMENT).toList()) {
                candidateTexts.put(
                        blockKey(candidate.document().documentId(), block.block().blockId()),
                        semanticCandidateText(
                                candidate.document(),
                                block,
                                evidenceWindow(block, documentBlocks),
                                MAX_BLOCK_RERANK_EVIDENCE_CHARS));
            }
        }
        if (candidateTexts.isEmpty()) {
            return Map.of();
        }
        List<String> keys = new ArrayList<>(candidateTexts.keySet());
        List<String> texts = keys.stream().map(candidateTexts::get).toList();
        try {
            List<Double> scores = vectorIndexService.rerankTexts(normalizedQuery, texts);
            Map<String, Double> scoreByKey = new LinkedHashMap<>();
            for (int index = 0; index < keys.size() && index < scores.size(); index += 1) {
                scoreByKey.put(keys.get(index), scores.get(index));
            }
            return Map.copyOf(scoreByKey);
        } catch (RuntimeException exception) {
            log.warn("teacher_resource_search_block_rerank_fallback query={} message={}",
                    normalizedQuery,
                    textOrDefault(exception.getMessage(), ""),
                    exception);
            return Map.of();
        }
    }

    /**
     * Legacy role-bucket heuristics were intentionally removed here. The previous implementation tried to infer
     * "analysis/question/lesson" intent from hand-written cue lists and then override the semantic ranking. That made
     * retrieval behavior brittle and benchmark-sensitive. The rewritten pipeline keeps blockRole/sourcePath/chapter/
     * section and the adjacent evidence window inside the rerank text itself, so the real rerank model stays primary
     * while lexical and graph signals only break ties.
     */
    private static Comparator<BlockCandidate> blockCandidateComparator() {
        Comparator<BlockCandidate> comparator = Comparator.comparingDouble(BlockCandidate::rerankScore).reversed();
        comparator = comparator.thenComparing(Comparator.comparingDouble(BlockCandidate::documentRerankScore).reversed());
        comparator = comparator.thenComparing(Comparator.comparingDouble(BlockCandidate::vectorSemanticScore).reversed());
        comparator = comparator.thenComparing(Comparator.comparingInt(BlockCandidate::lexicalMatches).reversed());
        return comparator.thenComparing(Comparator.comparingInt(BlockCandidate::graphMatches).reversed());
    }

    private static String semanticCandidateText(
            TeacherResourceDocumentResponse document,
            BlockContext block,
            EvidenceWindow evidence,
            int evidenceCharBudget) {
        String imageRefs = truncateForRerank(
                String.join(" ", parseStringArray(block.block().imageRefs())),
                MAX_RERANK_IMAGE_REFS_CHARS);
        String evidenceText = textOrDefault(
                evidence == null ? "" : evidence.text(),
                textOrDefault(block.block().normalizedText(), block.block().rawText()));
        return String.join(
                "\n",
                "documentTitle: " + truncateForRerank(textOrDefault(document.title(), ""), MAX_RERANK_TITLE_CHARS),
                "library: " + TeacherResourceLibraryResolver.effectiveLibrary(document),
                "role: " + truncateForRerank(textOrDefault(block.blockRole(), ""), MAX_RERANK_ROLE_CHARS),
                "chapter: " + truncateForRerank(textOrDefault(block.block().chapter(), ""), MAX_RERANK_HEADING_CHARS),
                "section: " + truncateForRerank(textOrDefault(block.block().section(), ""), MAX_RERANK_HEADING_CHARS),
                "sourcePath: " + truncateForRerank(textOrDefault(block.sourcePath(), ""), MAX_RERANK_SOURCE_PATH_CHARS),
                "graphTags: " + truncateForRerank(String.join(" ", block.graphTags()), MAX_RERANK_GRAPH_TAGS_CHARS),
                "imageRefs: " + imageRefs,
                "evidenceBlockIds: " + truncateForRerank(
                        String.join(" ", evidence == null ? List.of() : evidence.blockIds()),
                        MAX_RERANK_EVIDENCE_BLOCK_IDS_CHARS),
                "evidenceText:\n" + truncateForRerank(evidenceText, evidenceCharBudget));
    }

    private static int blockLexicalMatchCount(
            TeacherResourceDocumentResponse document,
            BlockContext block,
            String normalizedQuery,
            String[] terms) {
        String haystack = normalizeText(String.join(
                " ",
                textOrDefault(document.title(), ""),
                textOrDefault(block.block().chapter(), ""),
                textOrDefault(block.block().section(), ""),
                textOrDefault(block.sourcePath(), ""),
                textOrDefault(block.blockRole(), ""),
                textOrDefault(block.block().normalizedText(), block.block().rawText())));
        return lexicalMatchCount(haystack, normalizedQuery, terms);
    }

    private static int lexicalMatchCount(String haystack, String normalizedQuery, String[] terms) {
        String normalizedHaystack = normalizeText(textOrDefault(haystack, ""));
        if (normalizedHaystack.isBlank()) {
            return 0;
        }
        int matches = normalizedHaystack.contains(normalizedQuery) ? 1 : 0;
        for (String term : terms) {
            if (!term.isBlank() && normalizedHaystack.contains(term)) {
                matches += 1;
            }
        }
        return matches;
    }

    private static int graphAlignmentMatchCount(
            List<String> graphTags,
            List<String> graphNodeIds,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph,
            String normalizedQuery,
            String[] terms) {
        int matches = lexicalMatchCount(String.join(" ", graphTags == null ? List.of() : graphTags), normalizedQuery, terms);
        if (queryGraph == null || queryGraph.empty()) {
            return matches;
        }
        Set<String> blockNodeIdSet = new LinkedHashSet<>(graphNodeIds == null ? List.of() : graphNodeIds);
        for (String nodeId : queryGraph.primaryNodeIds()) {
            if (blockNodeIdSet.contains(nodeId)) {
                matches += 1;
            }
        }
        for (String nodeId : queryGraph.expandedNodeIds()) {
            if (blockNodeIdSet.contains(nodeId)) {
                matches += 1;
            }
        }
        return matches;
    }

    private static TeacherResourceBlockSearchResponse.Hit toTwoStageHit(
            BlockCandidate candidate,
            List<BlockContext> documentBlocks,
            String normalizedQuery,
            String[] terms) {
        BlockContext context = candidate.block();
        EvidenceWindow evidence = evidenceWindow(context, documentBlocks);
        return new TeacherResourceBlockSearchResponse.Hit(
                candidate.document().documentId(),
                candidate.document().title(),
                TeacherResourceLibraryResolver.effectiveLibrary(candidate.document()),
                candidate.document().permissionScope(),
                context.block().blockId(),
                context.block().blockType(),
                context.block().blockOrder(),
                context.block().chapter(),
                context.block().section(),
                context.block().pageNo(),
                context.sourcePath(),
                context.blockRole(),
                context.graphTags(),
                evidence.blockIds(),
                evidence.text(),
                snippet(textOrDefault(context.block().rawText(), context.block().normalizedText()), normalizedQuery, terms),
                candidate.rerankScore(),
                parseStringArray(context.block().imageRefs()),
                List.of());
    }

    /**
     * Returns the central hit block plus adjacent parsed neighbors. We do not expand arbitrarily: the goal is to keep
     * citations stable while still handling real evidence that spans a prompt block and the following answer/analysis.
     */
    private static EvidenceWindow evidenceWindow(BlockContext target, List<BlockContext> documentBlocks) {
        if (documentBlocks == null || documentBlocks.isEmpty()) {
            return new EvidenceWindow(List.of(target.block().blockId()), textOrDefault(target.block().rawText(), target.block().normalizedText()));
        }
        int targetIndex = -1;
        for (int index = 0; index < documentBlocks.size(); index += 1) {
            if (documentBlocks.get(index).block().blockId().equals(target.block().blockId())) {
                targetIndex = index;
                break;
            }
        }
        if (targetIndex < 0) {
            return new EvidenceWindow(List.of(target.block().blockId()), textOrDefault(target.block().rawText(), target.block().normalizedText()));
        }
        List<String> blockIds = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        int start = Math.max(0, targetIndex - EVIDENCE_WINDOW_RADIUS);
        int end = Math.min(documentBlocks.size() - 1, targetIndex + EVIDENCE_WINDOW_RADIUS);
        for (int index = start; index <= end; index += 1) {
            BlockContext neighbor = documentBlocks.get(index);
            blockIds.add(neighbor.block().blockId());
            String text = textOrDefault(neighbor.block().rawText(), neighbor.block().normalizedText());
            if (!text.isBlank()) {
                texts.add(text);
            }
        }
        return new EvidenceWindow(List.copyOf(blockIds), String.join("\n", texts));
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

    private static BlockContext toContext(
            TeacherResourceDocumentResponse document,
            TeacherDocumentBlockResponse block) {
        return new BlockContext(
                document,
                block,
                normalizeText(textOrDefault(block.normalizedText(), block.rawText())),
                textOrDefault(block.sourcePath(), ""),
                textOrDefault(block.blockRole(), "reference"),
                parseStringArray(block.graphTagNamesJson()),
                parseStringArray(block.graphNodeIdsJson()));
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
                safeRetrievalMode(retrievalMode),
                hits.size(),
                hits);
    }

    /**
     * Translates one textbook page hit into the teacher-search hit contract so callers can keep one response parser.
     * The synthetic sourcePath is stable and clearly marks that this evidence came from processed_books rather than a
     * teacher-uploaded document row.
     */
    private static TeacherResourceBlockSearchResponse.Hit textbookHit(TextbookSearchHit hit) {
        String section = textOrDefault(hit.sectionTitle(), "");
        String chapter = hit.chapterPath() == null || hit.chapterPath().isEmpty()
                ? ""
                : String.join(" / ", hit.chapterPath());
        String evidenceText = textOrDefault(hit.textSnippet(), "");
        if (hit.formulaText() != null && !hit.formulaText().isBlank()) {
            evidenceText = evidenceText.isBlank() ? hit.formulaText() : evidenceText + "\n" + hit.formulaText();
        }
        String sourcePath = "textbook://" + hit.docId() + "/page/" + hit.pageNo() + "#chunk=" + hit.chunkId();
        return new TeacherResourceBlockSearchResponse.Hit(
                hit.docId(),
                hit.bookName(),
                "public_textbook",
                "PUBLIC_TEXTBOOK",
                hit.chunkId(),
                "page_chunk",
                Math.max(hit.pageNo(), 0),
                chapter,
                section,
                hit.pageNo(),
                sourcePath,
                "reference",
                List.of(),
                List.of(hit.chunkId()),
                evidenceText,
                textOrDefault(hit.textSnippet(), evidenceText),
                hit.score(),
                List.of(),
                List.of());
    }

    /**
     * Textbook page-image retrieval returns page-level evidence from the same processed_books corpus, but through the
     * CLIP index maintained by the Python worker. We map it into the same teacher-search hit contract so it can join
     * the final semantic merge with text chunks.
     */
    private static TeacherResourceBlockSearchResponse.Hit textbookImageHit(TextbookPageImageSearchHit hit) {
        String blockId = hit.docId() + "_p" + hit.pageNo() + "_clip";
        String sourcePath = "textbook://" + hit.docId() + "/page/" + hit.pageNo() + "#clip=page";
        String evidenceText = textOrDefault(hit.text(), "");
        return new TeacherResourceBlockSearchResponse.Hit(
                hit.docId(),
                hit.bookName(),
                "public_textbook",
                "PUBLIC_TEXTBOOK",
                blockId,
                "page_image",
                Math.max(hit.pageNo(), 0),
                textOrDefault(hit.chapterPath(), ""),
                textOrDefault(hit.sectionTitle(), ""),
                hit.pageNo(),
                sourcePath,
                "reference",
                List.of(),
                List.of(blockId),
                evidenceText,
                evidenceText,
                hit.score(),
                List.of(),
                List.of());
    }

    /**
     * Cross-source merging now uses the same real rerank primitive as the main two-stage pipeline instead of reciprocal
     * rank math. That keeps teacher blocks, textbook chunks, and textbook page-image hits in one semantic ranking
     * space, while lexical overlap stays only as a tie-breaker.
     */
    private List<TeacherResourceBlockSearchResponse.Hit> semanticMergeHits(
            String normalizedQuery,
            List<TeacherResourceBlockSearchResponse.Hit> hits,
            int safeLimit) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        Map<String, TeacherResourceBlockSearchResponse.Hit> deduplicated = new LinkedHashMap<>();
        for (TeacherResourceBlockSearchResponse.Hit hit : hits) {
            if (hit == null) {
                continue;
            }
            String key = blockKey(hit.documentId(), hit.blockId());
            deduplicated.merge(key, hit, TeacherResourceBlockSearchService::preferMergeHit);
        }
        List<TeacherResourceBlockSearchResponse.Hit> candidates = List.copyOf(deduplicated.values());
        List<String> candidateTexts = candidates.stream()
                .map(TeacherResourceBlockSearchService::semanticMergeCandidateText)
                .toList();
        List<Double> rerankScores = vectorIndexService.rerankTexts(normalizedQuery, candidateTexts);
        String[] terms = searchTerms(normalizedQuery);
        List<MergeCandidate> mergeCandidates = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index += 1) {
            TeacherResourceBlockSearchResponse.Hit hit = candidates.get(index);
            mergeCandidates.add(new MergeCandidate(
                    hit,
                    rerankScores.size() > index ? rerankScores.get(index) : hit.score(),
                    lexicalMatchCount(candidateTexts.get(index), normalizedQuery, terms)));
        }
        return mergeCandidates.stream()
                .sorted(Comparator.comparingDouble(MergeCandidate::rerankScore).reversed()
                        .thenComparing(Comparator.comparingInt(MergeCandidate::lexicalMatches).reversed())
                        .thenComparing(Comparator.comparingDouble(MergeCandidate::sourceScore).reversed())
                        .thenComparing(candidate -> candidate.hit().documentTitle())
                        .thenComparing(candidate -> candidate.hit().documentId())
                        .thenComparing(candidate -> candidate.hit().blockId()))
                .limit(safeLimit)
                .map(candidate -> withScore(candidate.hit(), candidate.rerankScore()))
                .toList();
    }

    private static TeacherResourceBlockSearchResponse.Hit preferMergeHit(
            TeacherResourceBlockSearchResponse.Hit left,
            TeacherResourceBlockSearchResponse.Hit right) {
        int leftEvidenceLength = textOrDefault(left.evidenceText(), left.snippet()).length();
        int rightEvidenceLength = textOrDefault(right.evidenceText(), right.snippet()).length();
        if (rightEvidenceLength > leftEvidenceLength) {
            return right.score() >= left.score() ? right : withScore(right, left.score());
        }
        return left.score() >= right.score() ? left : withScore(left, right.score());
    }

    private static String semanticMergeCandidateText(TeacherResourceBlockSearchResponse.Hit hit) {
        return String.join(
                "\n",
                "documentTitle: " + truncateForRerank(textOrDefault(hit.documentTitle(), ""), MAX_RERANK_TITLE_CHARS),
                "sourceType: " + textOrDefault(hit.sourceType(), ""),
                "blockRole: " + truncateForRerank(textOrDefault(hit.blockRole(), ""), MAX_RERANK_ROLE_CHARS),
                "chapter: " + truncateForRerank(textOrDefault(hit.chapter(), ""), MAX_RERANK_HEADING_CHARS),
                "section: " + truncateForRerank(textOrDefault(hit.section(), ""), MAX_RERANK_HEADING_CHARS),
                "sourcePath: " + truncateForRerank(textOrDefault(hit.sourcePath(), ""), MAX_RERANK_SOURCE_PATH_CHARS),
                "graphTags: " + truncateForRerank(
                        String.join(" ", hit.graphTags() == null ? List.of() : hit.graphTags()),
                        MAX_RERANK_GRAPH_TAGS_CHARS),
                "evidenceText:\n" + truncateForRerank(
                        textOrDefault(hit.evidenceText(), hit.snippet()),
                        MAX_MERGE_RERANK_EVIDENCE_CHARS));
    }

    private static TeacherResourceBlockSearchResponse.Hit withScore(
            TeacherResourceBlockSearchResponse.Hit hit,
            double score) {
        return new TeacherResourceBlockSearchResponse.Hit(
                hit.documentId(),
                hit.documentTitle(),
                hit.sourceType(),
                hit.permissionScope(),
                hit.blockId(),
                hit.blockType(),
                hit.blockOrder(),
                hit.chapter(),
                hit.section(),
                hit.pageNo(),
                hit.sourcePath(),
                hit.blockRole(),
                hit.graphTags(),
                hit.evidenceBlockIds(),
                hit.evidenceText(),
                hit.snippet(),
                score,
                hit.imageAssetIds(),
                hit.assetRefs());
    }

    /**
     * Asset URLs are attached only after ranking so retrieval math stays content-driven while final hits still carry
     * safe image references for UI/AI rendering.
     */
    private TeacherResourceBlockSearchResponse attachVisibleAssetRefs(
            TeacherResourceBlockSearchResponse response,
            RequestSubject subject) {
        if (response == null || response.hits() == null || response.hits().isEmpty()) {
            return response;
        }
        List<TeacherResourceBlockSearchResponse.Hit> hits = response.hits().stream()
                .map(hit -> attachVisibleAssetRefs(hit, subject))
                .toList();
        return new TeacherResourceBlockSearchResponse(
                response.queryId(),
                response.query(),
                response.limit(),
                safeRetrievalMode(response.retrievalMode()),
                hits.size(),
                hits);
    }

    private TeacherResourceBlockSearchResponse.Hit attachVisibleAssetRefs(
            TeacherResourceBlockSearchResponse.Hit hit,
            RequestSubject subject) {
        if (hit == null || hit.imageAssetIds() == null || hit.imageAssetIds().isEmpty()) {
            return hit == null ? null : hit.withAssetRefs(List.of());
        }
        List<TeacherResourceBlockSearchResponse.AssetRef> assetRefs = hit.imageAssetIds().stream()
                .map(assetId -> assetService.findVisibleAssetReference(assetId, subject))
                .flatMap(Optional::stream)
                .map(TeacherResourceAssetService.VisibleAssetReference::toSearchAssetRef)
                .toList();
        return hit.withAssetRefs(assetRefs);
    }

    /**
     * Ensures only teacher/admin backend subjects can use this teacher resource endpoint.
     */
    private static void requireTeacherOrAdmin(String viewerRole) {
        if (!"teacher".equals(viewerRole) && !"admin".equals(viewerRole)) {
            throw new IllegalArgumentException("Teacher resource block search requires teacher or admin role");
        }
    }

    private static List<TeacherResourceDocumentResponse> filteredDocuments(
            List<TeacherResourceDocumentResponse> documents,
            TeacherResourceSearchFilter filter) {
        if (filter.empty()) {
            return documents;
        }
        return documents.stream()
                .filter(document -> filter.documentIds().isEmpty() || filter.documentIds().contains(document.documentId()))
                .filter(document -> filter.permissionScopes().isEmpty()
                        || filter.permissionScopes().contains(textOrDefault(document.permissionScope(), "").toUpperCase(Locale.ROOT)))
                .filter(document -> TeacherResourceLibraryResolver.matchesAny(document, filter.sourceTypes()))
                .toList();
    }

    private boolean shouldUseRealTextbook(TeacherResourceSearchFilter filter) {
        if (!realTextbookAvailable()) {
            return false;
        }
        if (filter == null) {
            return true;
        }
        if (filter.documentIds() != null && !filter.documentIds().isEmpty()) {
            /*
             * Teacher-document ids refer to source_document rows, not processed_books textbook doc ids. Do not inject
             * textbook corpus hits into a doc-id scoped query because that would violate the caller's explicit scope.
             */
            return false;
        }
        if (filter.sourceTypes() == null || filter.sourceTypes().isEmpty()) {
            return true;
        }
        return filter.sourceTypes().stream()
                .map(TeacherResourceBlockSearchService::normalizeText)
                .anyMatch(selector -> "textbook".equals(selector) || "public_textbook".equals(selector));
    }

    /**
     * If the caller narrowed the search space to textbook only, there is no value in running teacher-resource stage
     * one first. The real textbook retriever is already the canonical source for that library and produces a cleaner
     * candidate pool for the final merge.
     */
    private static boolean isTextbookOnlyFilter(TeacherResourceSearchFilter filter) {
        if (filter == null) {
            return false;
        }
        if ((filter.documentIds() != null && !filter.documentIds().isEmpty())
                || (filter.permissionScopes() != null && !filter.permissionScopes().isEmpty())
                || (filter.tags() != null && !filter.tags().isEmpty())) {
            return false;
        }
        if (filter.sourceTypes() == null || filter.sourceTypes().isEmpty()) {
            return false;
        }
        return filter.sourceTypes().stream()
                .map(TeacherResourceBlockSearchService::normalizeText)
                .allMatch(selector -> "textbook".equals(selector) || "public_textbook".equals(selector));
    }

    private boolean realTextbookAvailable() {
        return textbookRetrievalService != null && textbookResourceProperties != null;
    }

    private static boolean matchesTags(
            TeacherResourceDocumentResponse document,
            TeacherDocumentBlockResponse block,
            List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return true;
        }
        String haystack = normalizeText(String.join(
                " ",
                textOrDefault(document.title(), ""),
                TeacherResourceLibraryResolver.effectiveLibrary(document),
                textOrDefault(block.chapter(), ""),
                textOrDefault(block.section(), ""),
                textOrDefault(block.sourcePath(), ""),
                textOrDefault(block.blockRole(), ""),
                String.join(" ", parseStringArray(block.graphTagNamesJson())),
                textOrDefault(block.normalizedText(), block.rawText())));
        return tags.stream().map(TeacherResourceBlockSearchService::normalizeText).anyMatch(haystack::contains);
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

    private static String retrievalMode(String strategy, TeacherResourceSearchFilter filter, String suffix) {
        String mode = filter.empty() ? strategy : strategy + "_filtered";
        if (suffix == null || suffix.isBlank()) {
            return safeRetrievalMode(mode);
        }
        return safeRetrievalMode(mode + "_" + suffix);
    }

    /**
     * Audit rows persist retrieval mode in a compact varchar column. Truncation here is intentional: the response
     * label remains human-readable while search continues to work even if internal stage names evolve.
     */
    private static String safeRetrievalMode(String retrievalMode) {
        String normalized = textOrDefault(retrievalMode, "").strip();
        if (normalized.length() <= MAX_RETRIEVAL_MODE_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_RETRIEVAL_MODE_LENGTH);
    }

    /**
     * Splits a normalized query into non-empty terms.
     */
    private static String[] searchTerms(String normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            return new String[0];
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String fragment : normalizedQuery.split("\\s+")) {
            appendSearchTerms(terms, fragment);
            if (terms.size() >= MAX_SEARCH_TERMS) {
                break;
            }
        }
        return terms.stream()
                .limit(MAX_SEARCH_TERMS)
                .toArray(String[]::new);
    }

    /**
     * Extracts a compact set of lexical terms from Chinese and Latin fragments. Runtime eval queries are often written
     * as one continuous Chinese sentence with no spaces, so whitespace tokenization alone collapses the entire query
     * into one unusable term and erases the lexical signals that should help stage one find the right document.
     */
    private static void appendSearchTerms(LinkedHashSet<String> terms, String fragment) {
        String normalizedFragment = normalizeText(textOrDefault(fragment, ""));
        if (normalizedFragment.isBlank()) {
            return;
        }
        addSearchTerm(terms, normalizedFragment);
        StringBuilder latin = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int index = 0; index < normalizedFragment.length(); index += 1) {
            char current = normalizedFragment.charAt(index);
            if (isAsciiWordChar(current)) {
                if (cjk.length() > 0) {
                    appendCjkTerms(terms, cjk.toString());
                    cjk.setLength(0);
                }
                latin.append(current);
            } else if (isCjkChar(current)) {
                if (latin.length() > 0) {
                    addSearchTerm(terms, latin.toString());
                    latin.setLength(0);
                }
                cjk.append(current);
            } else {
                if (latin.length() > 0) {
                    addSearchTerm(terms, latin.toString());
                    latin.setLength(0);
                }
                if (cjk.length() > 0) {
                    appendCjkTerms(terms, cjk.toString());
                    cjk.setLength(0);
                }
            }
            if (terms.size() >= MAX_SEARCH_TERMS) {
                return;
            }
        }
        if (latin.length() > 0) {
            addSearchTerm(terms, latin.toString());
        }
        if (cjk.length() > 0) {
            appendCjkTerms(terms, cjk.toString());
        }
    }

    private static void appendCjkTerms(LinkedHashSet<String> terms, String fragment) {
        if (fragment.isBlank()) {
            return;
        }
        if (fragment.length() <= 6) {
            addSearchTerm(terms, fragment);
        }
        if (fragment.length() == 1) {
            return;
        }
        for (int index = 0; index < fragment.length() - 1 && terms.size() < MAX_SEARCH_TERMS; index += 1) {
            addSearchTerm(terms, fragment.substring(index, index + 2));
        }
    }

    private static void addSearchTerm(LinkedHashSet<String> terms, String candidate) {
        String normalizedCandidate = normalizeText(textOrDefault(candidate, ""));
        if (normalizedCandidate.isBlank()) {
            return;
        }
        if (normalizedCandidate.length() == 1 && isAsciiWordChar(normalizedCandidate.charAt(0))) {
            /*
             * Single ASCII letters are almost always retrieval noise in real teacher queries: articles like "a",
             * variable fragments, and OCR leftovers would otherwise match nearly every Latin block and make filtered
             * library rejection impossible. Keep one-character CJK terms available, but drop one-character ASCII terms.
             */
            return;
        }
        terms.add(normalizedCandidate);
    }

    /**
     * Normalizes searchable text by case folding and whitespace compaction.
     */
    private static String normalizeText(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    /**
     * Real teacher folders can carry very long paths, neighboring block windows, and image references. Truncate only
     * the rerank view so the worker gets the strongest semantic clues without timing out; the response still returns
     * the original evidence text and snippets elsewhere.
     */
    private static String truncateForRerank(String value, int maxChars) {
        String normalized = textOrDefault(value, "");
        if (normalized.isBlank() || maxChars <= 0 || normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars).strip() + "…";
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

    private static boolean containsAny(String haystack, String... needles) {
        String normalizedHaystack = normalizeText(textOrDefault(haystack, ""));
        for (String needle : needles) {
            if (!needle.isBlank() && normalizedHaystack.contains(normalizeText(needle))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiWordChar(char value) {
        return (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9')
                || value == '_'
                || value == '-';
    }

    private static boolean isCjkChar(char value) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(value);
        return Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS.equals(block)
                || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A.equals(block)
                || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B.equals(block)
                || Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS.equals(block);
    }

    private static List<String> parseStringArray(String json) {
        String value = textOrDefault(json, "[]");
        try {
            return OBJECT_MAPPER.readValue(value, STRING_LIST_TYPE).stream()
                    .filter(item -> item != null && !item.isBlank())
                    .map(String::strip)
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String blockKey(String documentId, String blockId) {
        return documentId + ":" + blockId;
    }

    private record BlockContext(
            TeacherResourceDocumentResponse document,
            TeacherDocumentBlockResponse block,
            String searchableText,
            String sourcePath,
            String blockRole,
            List<String> graphTags,
            List<String> graphNodeIds) {
    }

    private record DocumentCandidate(
            TeacherResourceDocumentResponse document,
            List<BlockContext> blocks,
            double semanticScore,
            double vectorScore,
            int lexicalMatches,
            int graphMatches) {
        private DocumentCandidate withSemanticScore(double rerankedSemanticScore) {
            return new DocumentCandidate(document, blocks, rerankedSemanticScore, vectorScore, lexicalMatches, graphMatches);
        }
    }

    private record BlockCandidate(
            TeacherResourceDocumentResponse document,
            BlockContext block,
            double rerankScore,
            int lexicalMatches,
            double documentRerankScore,
            int graphMatches,
            double vectorSemanticScore) {
    }

    private record EvidenceWindow(
            List<String> blockIds,
            String text) {
    }

    private record MergeCandidate(
            TeacherResourceBlockSearchResponse.Hit hit,
            double rerankScore,
            int lexicalMatches) {
        private double sourceScore() {
            return hit.score();
        }
    }

}

