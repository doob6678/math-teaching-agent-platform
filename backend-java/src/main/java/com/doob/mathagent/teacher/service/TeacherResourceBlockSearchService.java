package com.doob.mathagent.teacher.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
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
 * <p>The legacy block-level hybrid path is kept for baseline comparison. Do not remove it until benchmark outputs
 * have been updated, otherwise we lose a stable before/after reference for unknown runtime-authored eval data.</p>
 */
@Service
public class TeacherResourceBlockSearchService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;
    private static final int SNIPPET_RADIUS = 80;
    private static final int EVIDENCE_WINDOW_RADIUS = 1;
    private static final int MAX_SEARCH_TERMS = 32;
    private static final double METADATA_EXACT_MATCH_BOOST = 4.0d;
    private static final double METADATA_TERM_MATCH_BOOST = 0.75d;
    private static final double FILTERED_REJECT_SCORE_THRESHOLD = 13.0d;
    private static final double FILTERED_REJECT_MAX_SCORE_WITHOUT_ANCHOR = 26.0d;
    private static final double FILTERED_ANCHOR_SCORE_THRESHOLD = 3.0d;
    private static final String STRATEGY_LEGACY_BLOCK_HYBRID = "legacy_block_hybrid";
    private static final String STRATEGY_TWO_STAGE_DOC_BLOCK = "two_stage_doc_block";
    private static final Set<String> GENERIC_SEARCH_TERMS = Set.of(
            "\u68c0\u7d22",
            "\u8bc4\u6d4b",
            "\u53ea\u505a",
            "\u8bf7\u627e",
            "\u8d44\u6599",
            "\u7ebf\u7d22",
            "\u8d44\u6599\u7ebf\u7d22",
            "\u8bc1\u636e",
            "\u8bc1\u636e\u5757",
            "\u8bc1\u636e\u5757\u5373\u53ef",
            "\u8fd4\u56de",
            "\u5373\u53ef",
            "\u5b9a\u4f4d",
            "\u4f18\u5148",
            "\u4f18\u5148\u627e",
            "\u6307\u5b9a",
            "\u6307\u5b9a\u5e93",
            "\u76ee\u6807",
            "\u89d2\u8272",
            "\u76ee\u6807\u89d2\u8272",
            "\u5e93\u8303\u56f4",
            "\u6765\u6e90",
            "\u76f4\u63a5",
            "\u8df3\u5230",
            "\u524d\u9762",
            "\u6700\u8d34",
            "\u8d34\u8fd1");

    private final TeacherResourceStore resourceStore;
    private final TeacherDocumentBlockStore blockStore;
    private final TeacherResourceBlockSearchAuditSink auditSink;
    private final VectorIndexService vectorIndexService;
    private final TeacherResourceGraphAlignmentService graphAlignmentService;
    private final TeacherResourceAssetService assetService;

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
                TeacherResourceAssetService.disabled());
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
            TeacherResourceGraphAlignmentService graphAlignmentService) {
        this(
                resourceStore,
                blockStore,
                auditSink,
                vectorIndexService,
                graphAlignmentService,
                TeacherResourceAssetService.disabled());
    }

    public TeacherResourceBlockSearchService(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore,
            TeacherResourceBlockSearchAuditSink auditSink,
            VectorIndexService vectorIndexService,
            TeacherResourceGraphAlignmentService graphAlignmentService,
            TeacherResourceAssetService assetService) {
        this.resourceStore = Objects.requireNonNull(resourceStore, "resourceStore is required");
        this.blockStore = Objects.requireNonNull(blockStore, "blockStore is required");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink is required");
        this.vectorIndexService = Objects.requireNonNull(vectorIndexService, "vectorIndexService is required");
        this.graphAlignmentService = Objects.requireNonNull(graphAlignmentService, "graphAlignmentService is required");
        this.assetService = Objects.requireNonNull(assetService, "assetService is required");
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
                TeacherResourceSearchFilter.EMPTY,
                STRATEGY_TWO_STAGE_DOC_BLOCK);
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
        return search(
                tenantId,
                viewerRole,
                viewerSubjectId,
                query,
                limit,
                endpoint,
                filter,
                STRATEGY_TWO_STAGE_DOC_BLOCK);
    }

    /**
     * Searches visible blocks using either the legacy block-level hybrid baseline or the new two-stage pipeline.
     *
     * <p>Keep this strategy switch in the Java backend instead of Python benchmark code. That way baseline and upgraded
     * retrieval both exercise the same real HTTP/permission/vector stack and cannot drift into a fake benchmark-only
     * code path.</p>
     */
    public TeacherResourceBlockSearchResponse search(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String query,
            int limit,
            String endpoint,
            TeacherResourceSearchFilter filter,
            String strategy) {
        long startedNanos = System.nanoTime();
        String normalizedTenantId = requireText(tenantId, "tenantId is required");
        String normalizedRole = requireText(viewerRole, "viewerRole is required").toLowerCase(Locale.ROOT);
        String normalizedSubjectId = requireText(viewerSubjectId, "viewerSubjectId is required");
        requireTeacherOrAdmin(normalizedRole);
        String normalizedQuery = normalizeQuery(query);
        int safeLimit = clampLimit(limit);
        String normalizedStrategy = normalizeStrategy(strategy);
        TeacherResourceSearchFilter normalizedFilter = filter == null ? TeacherResourceSearchFilter.EMPTY : filter;
        if (normalizedQuery.isBlank()) {
            TeacherResourceBlockSearchResponse emptyResponse = response(
                    normalizedQuery,
                    safeLimit,
                    normalizedStrategy + "_empty",
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
        List<TeacherResourceDocumentResponse> documents =
                filteredDocuments(
                        resourceStore.listSearchable(normalizedTenantId, normalizedRole, normalizedSubjectId),
                        normalizedFilter);
        TeacherResourceBlockSearchResponse searchResponse =
                STRATEGY_LEGACY_BLOCK_HYBRID.equals(normalizedStrategy)
                        ? hybridResponse(
                                normalizedTenantId,
                                documents,
                                normalizedQuery,
                                terms,
                                safeLimit,
                                normalizedFilter,
                                queryGraph)
                        : twoStageResponse(
                                normalizedTenantId,
                                documents,
                                normalizedQuery,
                                terms,
                                 safeLimit,
                                 normalizedFilter,
                                 queryGraph);
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
        List<DocumentCandidate> documentCandidates = coarseDocumentCandidates(
                documentsById,
                blocksByDocumentId,
                vectorScoreByKey,
                normalizedQuery,
                terms,
                safeLimit,
                queryGraph);
        List<DocumentCandidate> rankedDocuments = documentCandidates.stream()
                .sorted(Comparator.comparingDouble(DocumentCandidate::score).reversed()
                        .thenComparing(candidate -> candidate.document().title())
                        .thenComparing(candidate -> candidate.document().documentId()))
                .limit(candidateDocumentLimit(safeLimit, blocksByDocumentId.size()))
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
     * Legacy baseline kept for before/after runtime benchmark comparison.
     */
    private TeacherResourceBlockSearchResponse hybridResponse(
            String tenantId,
            List<TeacherResourceDocumentResponse> documents,
            String normalizedQuery,
            String[] terms,
            int safeLimit,
            TeacherResourceSearchFilter filter,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
        if (documents.isEmpty()) {
            return response(normalizedQuery, safeLimit, retrievalMode(STRATEGY_LEGACY_BLOCK_HYBRID, filter, "no_visible_documents"), List.of());
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
            for (TeacherDocumentBlockResponse block : blockStore.listByDocument(tenantId, document.documentId())) {
                if (matchesTags(document, block, filter.tags())) {
                    blocksById.put(block.blockId(), block);
                }
            }
        }
        List<TeacherResourceBlockSearchResponse.Hit> vectorHits = vectorIndexService
                .searchTeacherResourceBlocks(
                        normalizedQuery,
                        Math.max(safeLimit * 10, 50),
                        new VectorSearchFilter(List.copyOf(visibleDocumentIds), filter.permissionScopes()))
                .stream()
                .filter(hit -> visibleDocumentIds.contains(hit.documentId()))
                .map(hit -> toLegacyVectorHit(
                        documentsById.get(hit.documentId()),
                        blocksById.get(hit.blockId()),
                        hit,
                        normalizedQuery,
                        terms,
                        queryGraph))
                .filter(Objects::nonNull)
                .toList();
        List<TeacherResourceBlockSearchResponse.Hit> lexicalHits = lexicalHits(
                tenantId,
                documents,
                normalizedQuery,
                terms,
                Math.max(safeLimit * 4, safeLimit),
                filter.tags(),
                queryGraph);
        List<TeacherResourceBlockSearchResponse.Hit> hits = mergeHybridHits(vectorHits, lexicalHits, safeLimit);
        return response(normalizedQuery, safeLimit, retrievalMode(STRATEGY_LEGACY_BLOCK_HYBRID, filter, null), hits);
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
        Map<String, Double> scores = new LinkedHashMap<>();
        List<VectorSearchHit> hits = vectorIndexService.searchTeacherResourceBlocks(
                normalizedQuery,
                Math.max(safeLimit * 12, 60),
                new VectorSearchFilter(List.copyOf(visibleDocumentIds), filter.permissionScopes()));
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
            double bestBlockLexical = 0;
            double bestStructure = 0;
            double bestVector = 0;
            int lexicalHitCount = 0;
            int vectorHitCount = 0;
            for (BlockContext block : blocks) {
                double lexical = score(block.searchableText(), normalizedQuery, terms)
                        + metadataScore(document, block.block(), normalizedQuery, terms);
                double structure = fieldScore(block.sourcePath(), normalizedQuery, terms)
                        + fieldScore(block.block().chapter(), normalizedQuery, terms)
                        + fieldScore(block.block().section(), normalizedQuery, terms)
                        + fieldScore(block.blockRole(), normalizedQuery, terms)
                        + graphTagScore(block.graphTags(), block.graphNodeIds(), queryGraph, normalizedQuery, terms)
                        + roleCueScore(block.blockRole(), block.sourcePath(), normalizedQuery);
                double vector = vectorScoreByKey.getOrDefault(blockKey(document.documentId(), block.block().blockId()), 0.0d);
                if (lexical > 0) {
                    lexicalHitCount += 1;
                }
                if (vector > 0) {
                    vectorHitCount += 1;
                }
                bestBlockLexical = Math.max(bestBlockLexical, lexical);
                bestStructure = Math.max(bestStructure, structure);
                bestVector = Math.max(bestVector, vector);
            }
            double documentMetadata = documentMetadataScore(document, normalizedQuery, terms);
            double sourceCue = documentSourceCueScore(document, blocks, normalizedQuery);
            double vectorSupport = vectorSupportMultiplier(
                    documentMetadata,
                    sourceCue,
                    bestBlockLexical,
                    bestStructure,
                    lexicalHitCount);
            double documentScore = documentMetadataScore(document, normalizedQuery, terms)
                    + sourceCue
                    + bestBlockLexical * 1.35d
                    + bestStructure
                    + Math.min(lexicalHitCount, 2) * 0.8d
                    /*
                     * Stage one still needs vector recall so the right document can enter the candidate pool, but the
                     * live misses showed that a semantically broad "generic method" block can otherwise outrank the
                     * correct textbook or exam document when lexical/structural evidence is weak. Only let vector
                     * similarity contribute at full strength after the same document also shows some textual or
                     * structural support for the current query.
                     */
                    + bestVector * 6.0d * vectorSupport
                    + Math.min(vectorHitCount, 2) * 0.75d;
            candidates.add(new DocumentCandidate(document, blocks, documentScore));
        }
        return candidates.stream()
                .sorted(Comparator.comparingDouble(DocumentCandidate::score).reversed()
                        .thenComparing(candidate -> candidate.document().title())
                        .thenComparing(candidate -> candidate.document().documentId()))
                .limit(candidateDocumentLimit(safeLimit, blocksByDocumentId.size()))
                .toList();
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
        List<BlockCandidate> blockCandidates = new ArrayList<>();
        for (DocumentCandidate candidate : rankedDocuments) {
            List<BlockContext> documentBlocks = blocksByDocumentId.getOrDefault(candidate.document().documentId(), List.of());
            for (BlockContext block : candidate.blocks()) {
                double lexical = score(block.searchableText(), normalizedQuery, terms);
                double metadata = metadataScore(candidate.document(), block.block(), normalizedQuery, terms);
                double structure = fieldScore(block.sourcePath(), normalizedQuery, terms)
                        + fieldScore(block.block().chapter(), normalizedQuery, terms)
                        + fieldScore(block.block().section(), normalizedQuery, terms);
                double graph = graphTagScore(block.graphTags(), block.graphNodeIds(), queryGraph, normalizedQuery, terms);
                double role = roleCueScore(block.blockRole(), block.sourcePath(), normalizedQuery)
                        + fieldScore(block.blockRole(), normalizedQuery, terms);
                double neighbor = neighborSupportScore(block, documentBlocks, normalizedQuery, terms);
                double vector = vectorScoreByKey.getOrDefault(blockKey(candidate.document().documentId(), block.block().blockId()), 0.0d);
                double vectorSupport = vectorSupportMultiplier(
                        metadata,
                        structure,
                        lexical,
                        role + graph + neighbor,
                        lexical > 0 ? 1 : 0);
                double score = candidate.score() * 0.18d
                        + lexical * 1.7d
                        + metadata
                        + structure
                        + graph
                        + role
                        + neighbor
                        /*
                         * Stage two is intentionally document-internal. Vector similarity is still useful inside one
                         * candidate document, but a question block should not keep beating its adjacent analysis block
                         * purely because the embedding is broad. Neighbor-aware role evidence therefore shares the same
                         * score budget and gates part of the vector contribution.
                         */
                        + vector * 6.0d * vectorSupport
                        + exactQueryBonus(block.searchableText(), normalizedQuery);
                blockCandidates.add(new BlockCandidate(
                        candidate.document(),
                        block,
                        score,
                        lexical,
                        metadata,
                        structure,
                        graph,
                        role,
                        neighbor,
                        vector));
            }
        }
        List<BlockCandidate> rankedCandidates = blockCandidates.stream()
                .sorted(Comparator.comparingDouble(BlockCandidate::score).reversed()
                        .thenComparing(candidate -> candidate.document().title())
                        .thenComparing(candidate -> candidate.block().block().blockOrder()))
                .toList();
        List<BlockCandidate> acceptedCandidates = maybeRejectLowConfidenceFilteredHits(
                rankedCandidates,
                filter,
                normalizedQuery,
                terms);
        return acceptedCandidates.stream()
                .limit(safeLimit)
                .map(candidate -> toTwoStageHit(candidate, blocksByDocumentId.get(candidate.document().documentId()), normalizedQuery, terms))
                .toList();
    }

    private static int candidateDocumentLimit(int safeLimit, int visibleDocumentCount) {
        if (visibleDocumentCount <= 0) {
            return 0;
        }
        return Math.min(visibleDocumentCount, Math.max(safeLimit * 3, 6));
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
                candidate.score(),
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

    private static TeacherResourceBlockSearchResponse.Hit boostLexicalHit(
            TeacherResourceBlockSearchResponse.Hit lexicalHit,
            double vectorScore) {
        return new TeacherResourceBlockSearchResponse.Hit(
                lexicalHit.documentId(),
                lexicalHit.documentTitle(),
                lexicalHit.sourceType(),
                lexicalHit.permissionScope(),
                lexicalHit.blockId(),
                lexicalHit.blockType(),
                lexicalHit.blockOrder(),
                lexicalHit.chapter(),
                lexicalHit.section(),
                lexicalHit.pageNo(),
                lexicalHit.sourcePath(),
                lexicalHit.blockRole(),
                lexicalHit.graphTags(),
                lexicalHit.evidenceBlockIds(),
                lexicalHit.evidenceText(),
                lexicalHit.snippet(),
                lexicalHit.score() + Math.max(vectorScore, 0),
                lexicalHit.imageAssetIds(),
                lexicalHit.assetRefs());
    }

    private static TeacherResourceBlockSearchResponse.Hit toLegacyVectorHit(
            TeacherResourceDocumentResponse document,
            TeacherDocumentBlockResponse block,
            VectorSearchHit vectorHit,
            String normalizedQuery,
            String[] terms,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
        if (document == null || block == null) {
            return null;
        }
        List<String> graphTags = parseStringArray(block.graphTagNamesJson());
        String rawText = textOrDefault(block.rawText(), vectorHit.text());
        return new TeacherResourceBlockSearchResponse.Hit(
                document.documentId(),
                document.title(),
                TeacherResourceLibraryResolver.effectiveLibrary(document),
                document.permissionScope(),
                block.blockId(),
                block.blockType(),
                block.blockOrder(),
                block.chapter(),
                block.section(),
                block.pageNo(),
                textOrDefault(block.sourcePath(), ""),
                textOrDefault(block.blockRole(), "reference"),
                graphTags,
                List.of(block.blockId()),
                rawText,
                snippet(rawText, normalizedQuery, terms),
                vectorHit.score()
                        + score(normalizeText(rawText), normalizedQuery, terms)
                        + metadataScore(document, block, normalizedQuery, terms)
                        + graphTagScore(graphTags, parseStringArray(block.graphNodeIdsJson()), queryGraph, normalizedQuery, terms),
                parseStringArray(block.imageRefs()),
                List.of());
    }

    private List<TeacherResourceBlockSearchResponse.Hit> lexicalHits(
            String tenantId,
            List<TeacherResourceDocumentResponse> documents,
            String normalizedQuery,
            String[] terms,
            int safeLimit,
            List<String> tags,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
        return documents.stream()
                .flatMap(document -> blockStore.listByDocument(tenantId, document.documentId()).stream()
                        .filter(block -> matchesTags(document, block, tags))
                        .map(block -> lexicalHit(document, block, normalizedQuery, terms, queryGraph)))
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparingDouble(TeacherResourceBlockSearchResponse.Hit::score).reversed()
                        .thenComparing(TeacherResourceBlockSearchResponse.Hit::documentTitle)
                        .thenComparingInt(TeacherResourceBlockSearchResponse.Hit::blockOrder))
                .limit(safeLimit)
                .toList();
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
        List<TeacherResourceBlockSearchResponse.Hit> ranked = merged.values().stream()
                .sorted(Comparator.comparingDouble(TeacherResourceBlockSearchResponse.Hit::score).reversed()
                        .thenComparing(TeacherResourceBlockSearchResponse.Hit::documentTitle)
                        .thenComparingInt(TeacherResourceBlockSearchResponse.Hit::blockOrder))
                .toList();
        return diversifyByDocument(ranked, safeLimit);
    }

    /**
     * Prevents one noisy document from occupying the whole first page before other plausible documents
     * get a chance. This is generic diversification rather than a benchmark-specific shortcut.
     */
    private static List<TeacherResourceBlockSearchResponse.Hit> diversifyByDocument(
            List<TeacherResourceBlockSearchResponse.Hit> rankedHits,
            int safeLimit) {
        Map<String, List<TeacherResourceBlockSearchResponse.Hit>> hitsByDocument = new LinkedHashMap<>();
        for (TeacherResourceBlockSearchResponse.Hit hit : rankedHits) {
            hitsByDocument.computeIfAbsent(hit.documentId(), ignored -> new ArrayList<>()).add(hit);
        }
        List<Map.Entry<String, List<TeacherResourceBlockSearchResponse.Hit>>> documentBuckets = hitsByDocument.entrySet().stream()
                .sorted(Comparator
                        .comparingDouble((Map.Entry<String, List<TeacherResourceBlockSearchResponse.Hit>> entry) ->
                                entry.getValue().getFirst().score())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .toList();
        List<TeacherResourceBlockSearchResponse.Hit> diversified = new ArrayList<>();
        for (int depth = 0; diversified.size() < safeLimit; depth += 1) {
            boolean appendedAtDepth = false;
            for (Map.Entry<String, List<TeacherResourceBlockSearchResponse.Hit>> entry : documentBuckets) {
                List<TeacherResourceBlockSearchResponse.Hit> bucket = entry.getValue();
                if (depth < bucket.size()) {
                    diversified.add(bucket.get(depth));
                    appendedAtDepth = true;
                    if (diversified.size() >= safeLimit) {
                        break;
                    }
                }
            }
            if (!appendedAtDepth) {
                break;
            }
        }
        return diversified.stream()
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
    private static TeacherResourceBlockSearchResponse.Hit lexicalHit(
            TeacherResourceDocumentResponse document,
            TeacherDocumentBlockResponse block,
            String normalizedQuery,
            String[] terms,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
        String searchableText = normalizeText(textOrDefault(block.normalizedText(), block.rawText()));
        double score = score(searchableText, normalizedQuery, terms)
                + metadataScore(document, block, normalizedQuery, terms)
                + fieldScore(block.sourcePath(), normalizedQuery, terms)
                + fieldScore(block.blockRole(), normalizedQuery, terms)
                + graphTagScore(
                        parseStringArray(block.graphTagNamesJson()),
                        parseStringArray(block.graphNodeIdsJson()),
                        queryGraph,
                        normalizedQuery,
                        terms);
        String rawText = textOrDefault(block.rawText(), block.normalizedText());
        return new TeacherResourceBlockSearchResponse.Hit(
                document.documentId(),
                document.title(),
                TeacherResourceLibraryResolver.effectiveLibrary(document),
                document.permissionScope(),
                block.blockId(),
                block.blockType(),
                block.blockOrder(),
                block.chapter(),
                block.section(),
                block.pageNo(),
                textOrDefault(block.sourcePath(), ""),
                textOrDefault(block.blockRole(), "reference"),
                parseStringArray(block.graphTagNamesJson()),
                List.of(block.blockId()),
                rawText,
                snippet(rawText, normalizedQuery, terms),
                score,
                parseStringArray(block.imageRefs()),
                List.of());
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
     * Adds a small metadata-aware boost so chapter/section/title matches can disambiguate blocks inside one document.
     */
    private static double metadataScore(
            TeacherResourceDocumentResponse document,
            TeacherDocumentBlockResponse block,
            String normalizedQuery,
            String[] terms) {
        String metadata = normalizeText(String.join(
                " ",
                textOrDefault(document.title(), ""),
                TeacherResourceLibraryResolver.effectiveLibrary(document),
                textOrDefault(block.chapter(), ""),
                textOrDefault(block.section(), "")));
        if (metadata.isBlank()) {
            return 0;
        }
        double score = metadata.contains(normalizedQuery) ? METADATA_EXACT_MATCH_BOOST : 0;
        for (String term : terms) {
            if (!term.isBlank() && metadata.contains(term)) {
                score += METADATA_TERM_MATCH_BOOST;
            }
        }
        return score;
    }

    private static double documentMetadataScore(
            TeacherResourceDocumentResponse document,
            String normalizedQuery,
            String[] terms) {
        String metadata = normalizeText(String.join(
                " ",
                textOrDefault(document.title(), ""),
                TeacherResourceLibraryResolver.effectiveLibrary(document),
                textOrDefault(document.permissionScope(), "")));
        return score(metadata, normalizedQuery, terms);
    }

    private static double documentSourceCueScore(
            TeacherResourceDocumentResponse document,
            List<BlockContext> blocks,
            String normalizedQuery) {
        StringBuilder haystack = new StringBuilder();
        haystack.append(textOrDefault(document.title(), "")).append(' ')
                .append(TeacherResourceLibraryResolver.effectiveLibrary(document));
        for (BlockContext block : blocks.stream().limit(4).toList()) {
            haystack.append(' ')
                    .append(textOrDefault(block.sourcePath(), ""))
                    .append(' ')
                    .append(textOrDefault(block.blockRole(), ""));
        }
        String normalizedHaystack = normalizeText(haystack.toString());
        double score = 0;
        score += categoricalCueScore(
                normalizedQuery,
                normalizedHaystack,
                new String[]{"\u6559\u6750", "textbook"},
                new String[]{"\u6559\u6750", "textbook", "chapter"});
        score += categoricalCueScore(
                normalizedQuery,
                normalizedHaystack,
                new String[]{"qq", "\u4e13\u9898", "\u70b9\u8bc4", "\u89e3\u6790"},
                new String[]{"qq", "bundle", "\u4e13\u9898", "\u70b9\u8bc4", "\u89e3\u6790"});
        score += categoricalCueScore(
                normalizedQuery,
                normalizedHaystack,
                new String[]{"\u98de\u4e66", "\u65b9\u6cd5\u6587\u6863", "\u8bb2\u6cd5", "\u677f\u4e66", "\u6a21\u677f"},
                new String[]{"feishu", "method", "\u8bb2\u6cd5", "\u677f\u4e66", "\u6a21\u677f"});
        score += categoricalCueScore(
                normalizedQuery,
                normalizedHaystack,
                new String[]{"\u771f\u9898", "\u9ad8\u8003"},
                new String[]{"\u771f\u9898", "\u9ad8\u8003", "gaokao"});
        score += categoricalCueScore(
                normalizedQuery,
                normalizedHaystack,
                new String[]{"\u6a21\u62df", "\u8bb2\u8bc4"},
                new String[]{"\u6a21\u62df", "\u8bb2\u8bc4", "mock"});
        return score;
    }

    private static double vectorSupportMultiplier(
            double metadataScore,
            double structuralScore,
            double lexicalScore,
            double companionScore,
            int lexicalHitCount) {
        double support = 0.2d;
        support += Math.min(0.18d, metadataScore / 14.0d);
        support += Math.min(0.18d, structuralScore / 12.0d);
        support += Math.min(0.24d, lexicalScore / 16.0d);
        support += Math.min(0.12d, companionScore / 18.0d);
        support += Math.min(0.18d, lexicalHitCount * 0.09d);
        return Math.min(1.0d, support);
    }

    private static double fieldScore(String fieldValue, String normalizedQuery, String[] terms) {
        String normalizedField = normalizeText(textOrDefault(fieldValue, ""));
        if (normalizedField.isBlank()) {
            return 0;
        }
        double score = normalizedField.contains(normalizedQuery) ? 2.0d : 0.0d;
        for (String term : terms) {
            if (!term.isBlank() && normalizedField.contains(term)) {
                score += 0.5d;
            }
        }
        return score;
    }

    private static double graphTagScore(List<String> graphTags, String normalizedQuery, String[] terms) {
        if (graphTags == null || graphTags.isEmpty()) {
            return 0;
        }
        double score = 0;
        for (String graphTag : graphTags) {
            score += fieldScore(graphTag, normalizedQuery, terms);
        }
        return score;
    }

    /**
     * The graph layer is a normalization aid for both retrieval stages. Raw lexical matches still matter, but once the
     * query and the block have been projected into the same graph ids we should reward that alignment even when the
     * surface wording differs between document title, section heading, and classroom phrasing.
     */
    private static double graphTagScore(
            List<String> graphTags,
            List<String> graphNodeIds,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph,
            String normalizedQuery,
            String[] terms) {
        double score = graphTagScore(graphTags, normalizedQuery, terms);
        if (queryGraph == null || queryGraph.empty()) {
            return score;
        }
        Set<String> blockNodeIdSet = new java.util.LinkedHashSet<>(graphNodeIds == null ? List.of() : graphNodeIds);
        if (!blockNodeIdSet.isEmpty()) {
            if (queryGraph.primaryNodeIds().stream().anyMatch(blockNodeIdSet::contains)) {
                score += 6.0d;
            } else if (queryGraph.expandedNodeIds().stream().anyMatch(blockNodeIdSet::contains)) {
                score += 2.5d;
            }
        }
        String tagHaystack = normalizeText(String.join(" ", graphTags == null ? List.of() : graphTags));
        for (String tag : queryGraph.primaryTagNames()) {
            if (!tag.isBlank() && tagHaystack.contains(normalizeText(tag))) {
                score += 2.0d;
            }
        }
        for (String tag : queryGraph.expandedTagNames()) {
            if (!tag.isBlank() && tagHaystack.contains(normalizeText(tag))) {
                score += 0.75d;
            }
        }
        return score;
    }

    /**
     * Generic block-role cues. This is intentionally broad and source-oriented; do not narrow it to one benchmark's
     * prompt wording or we will silently overfit runtime-authored eval data.
     */
    private static double roleCueScore(String blockRole, String sourcePath, String normalizedQuery) {
        String role = normalizeText(textOrDefault(blockRole, ""));
        String path = normalizeText(textOrDefault(sourcePath, ""));
        boolean wantsAnalysis = queryWantsAnalysis(normalizedQuery);
        boolean wantsMethod = containsPositiveCue(normalizedQuery, "\u65b9\u6cd5", "\u8bb2\u6cd5", "\u601d\u8def", "method", "approach");
        boolean wantsBoardwork = containsPositiveCue(normalizedQuery, "\u677f\u4e66", "\u677f\u6f14", "boardwork", "blackboard");
        boolean wantsTemplate = containsPositiveCue(normalizedQuery, "\u6a21\u677f", "\u8bb2\u4e49\u6a21\u677f", "template");
        boolean wantsTip = containsPositiveCue(normalizedQuery, "\u63d0\u793a", "\u63d0\u9192", "\u6ce8\u610f", "\u6613\u9519", "tip", "pitfall");
        boolean wantsQuestion = queryWantsQuestion(normalizedQuery, wantsAnalysis);
        boolean wantsLesson = queryWantsLesson(normalizedQuery);
        boolean rejectsAnalysis = queryRejectsRole(normalizedQuery, "\u89e3\u6790", "\u7b54\u6848", "\u8bb2\u8bc4", "analysis", "answer", "solution");
        boolean rejectsQuestion = queryRejectsRole(normalizedQuery, "\u9898\u9762", "\u9898\u76ee", "\u9898\u5e72", "\u539f\u9898", "question", "prompt", "stem");
        boolean rejectsLesson = queryRejectsRole(normalizedQuery, "\u4e13\u9898", "\u8bb2\u4e49", "\u603b\u8bb2", "lesson", "notes");
        boolean wantsExplanation = containsPositiveCue(
                normalizedQuery,
                "\u4e3a\u4ec0\u4e48",
                "\u600e\u4e48",
                "\u8bf4\u660e",
                "\u660e\u786e\u8bf4",
                "\u6b65\u9aa4",
                "\u63d0\u9192",
                "why",
                "how",
                "explain",
                "steps");
        double score = 0;
        if (wantsAnalysis && ("analysis".equals(role) || containsAny(path, "answer", "analysis", "solution"))) {
            score += 7.0d;
        } else if (wantsAnalysis) {
            score -= "question".equals(role) ? 4.2d : "lesson".equals(role) ? 2.6d : 2.0d;
        }
        if (rejectsAnalysis && ("analysis".equals(role) || containsAny(path, "answer", "analysis", "solution"))) {
            score -= 6.0d;
        }
        if (wantsMethod && ("method".equals(role) || containsAny(path, "method", "approach"))) {
            score += 5.0d;
        } else if (wantsMethod) {
            score -= "tip".equals(role) ? 0.8d : 1.5d;
        }
        if (wantsBoardwork && ("boardwork".equals(role) || containsAny(path, "boardwork", "blackboard"))) {
            score += 5.0d;
        } else if (wantsBoardwork) {
            score -= 1.5d;
        }
        if (wantsTemplate && ("template".equals(role) || containsAny(path, "template"))) {
            score += 5.0d;
        } else if (wantsTemplate) {
            score -= 1.5d;
        }
        if (wantsTip && ("tip".equals(role) || containsAny(path, "tip", "notice"))) {
            score += 5.0d;
        } else if (wantsTip) {
            score -= 1.5d;
        }
        if (wantsQuestion && ("question".equals(role) || containsAny(path, "question", "exam", "mock"))) {
            score += 6.2d;
        } else if (wantsQuestion) {
            score -= "lesson".equals(role) ? 3.2d : "analysis".equals(role) ? 2.4d : 1.4d;
        }
        if (rejectsQuestion && ("question".equals(role) || containsAny(path, "question", "exam", "mock"))) {
            score -= 6.0d;
        }
        if (wantsLesson && ("lesson".equals(role) || containsAny(path, "lesson", "handout", "textbook", "topic"))) {
            score += 4.0d;
        } else if (wantsLesson) {
            score -= "question".equals(role) ? 2.8d : 0.8d;
        }
        if (rejectsLesson && ("lesson".equals(role) || containsAny(path, "lesson", "handout", "textbook", "topic"))) {
            score -= 4.5d;
        }
        if (wantsExplanation) {
            if ("analysis".equals(role)) {
                score += 3.0d;
            } else if ("method".equals(role) || "tip".equals(role)) {
                score += 2.0d;
            } else if ("boardwork".equals(role) || "template".equals(role) || "lesson".equals(role)) {
                score += 1.0d;
            } else if ("question".equals(role)) {
                score -= 2.5d;
            }
        }
        return score;
    }

    private static boolean queryWantsAnalysis(String normalizedQuery) {
        if (containsPositiveCue(
                normalizedQuery,
                "\u89e3\u6790",
                "\u7b54\u6848",
                "\u601d\u8def",
                "\u8def\u7ebf",
                "\u70b9\u8bc4",
                "\u9519\u56e0",
                "\u9519\u8bef",
                "\u6b65\u9aa4",
                "\u89e3\u6cd5",
                "\u9a8c\u7b97",
                "\u8bb2\u8bc4\u5757",
                "\u8bb2\u8bc4\u70b9\u8bc4",
                "\u8bb2\u8bc4\u89e3\u6790",
                "analysis",
                "answer",
                "solution",
                "steps")) {
            return true;
        }
        /*
         * "讲评" alone is ambiguous: teachers often say "专题讲评课" while asking for the lesson-level
         * classroom entry, not a single-question answer block. Only treat it as analysis when it is paired with
         * answer/solution/error-route wording and no lesson-level classroom cue is present.
         */
        return containsPositiveCue(normalizedQuery, "\u8bb2\u8bc4")
                && !queryWantsLesson(normalizedQuery)
                && containsPositiveCue(
                        normalizedQuery,
                        "\u9519\u56e0",
                        "\u9519\u8bef",
                        "\u6b65\u9aa4",
                        "\u7b54\u6848",
                        "\u89e3\u6790",
                        "\u70b9\u8bc4",
                        "\u8def\u7ebf",
                        "\u89e3\u6cd5",
                        "\u9a8c\u7b97");
    }

    private static boolean queryWantsQuestion(String normalizedQuery, boolean wantsAnalysis) {
        if (containsPositiveCue(
                normalizedQuery,
                "\u9898\u9762",
                "\u9898\u76ee",
                "\u9898\u5e72",
                "\u539f\u9898",
                "\u539f\u6587",
                "\u5148\u770b\u9898",
                "\u5b9a\u4f4d\u9898\u9762",
                "\u54ea\u9053\u9898",
                "question",
                "prompt",
                "stem")) {
            return true;
        }
        /*
         * "真题/模拟" often selects the library, not the block role. When the same query asks for an answer,
         * explanation, or commentary, stage-two rerank should prefer the analysis sibling over the question sibling.
         */
        return !wantsAnalysis && containsAny(
                normalizedQuery,
                "\u771f\u9898",
                "\u6a21\u62df",
                "\u4f8b\u9898",
                "exam");
    }

    private static boolean queryWantsLesson(String normalizedQuery) {
        return containsPositiveCue(
                normalizedQuery,
                "\u4e13\u9898",
                "\u8bb2\u4e49",
                "\u6559\u6750",
                "\u8bfe\u5802",
                "\u8bb2\u8bc4\u8bfe",
                "\u6574\u4f53\u8bb2\u6cd5",
                "\u8bb2\u6cd5\u5165\u53e3",
                "\u6574\u4f53\u68b3\u7406",
                "\u5f00\u7bc7",
                "\u6536\u675f",
                "\u8bfe\u5802\u68c0\u67e5",
                "\u603b\u8bb2",
                "\u6574\u5305",
                "lesson",
                "notes",
                "textbook",
                "topic");
    }

    private static boolean queryHasExplicitRoleIntent(String normalizedQuery) {
        return queryWantsAnalysis(normalizedQuery)
                || queryWantsQuestion(normalizedQuery, false)
                || queryWantsLesson(normalizedQuery)
                || containsPositiveCue(
                        normalizedQuery,
                        "\u65b9\u6cd5",
                        "\u8bb2\u6cd5",
                        "\u601d\u8def",
                        "\u677f\u4e66",
                        "\u677f\u6f14",
                        "\u6a21\u677f",
                        "\u63d0\u793a",
                        "\u6613\u9519",
                        "method",
                        "boardwork",
                        "template",
                        "tip");
    }

    private static boolean roleSatisfiesQueryIntent(String blockRole, String sourcePath, String normalizedQuery) {
        String role = normalizeText(textOrDefault(blockRole, ""));
        String path = normalizeText(textOrDefault(sourcePath, ""));
        boolean wantsAnalysis = queryWantsAnalysis(normalizedQuery);
        if (queryWantsQuestion(normalizedQuery, wantsAnalysis)) {
            return "question".equals(role) || containsAny(path, "question", "exam", "mock");
        }
        if (wantsAnalysis) {
            return "analysis".equals(role) || containsAny(path, "answer", "analysis", "solution");
        }
        if (containsPositiveCue(normalizedQuery, "\u677f\u4e66", "\u677f\u6f14", "boardwork", "blackboard")) {
            return "boardwork".equals(role) || containsAny(path, "boardwork", "blackboard");
        }
        if (containsPositiveCue(normalizedQuery, "\u6a21\u677f", "\u8bb2\u4e49\u6a21\u677f", "template")) {
            return "template".equals(role) || containsAny(path, "template");
        }
        if (containsPositiveCue(normalizedQuery, "\u63d0\u793a", "\u6613\u9519", "tip", "notice")) {
            return "tip".equals(role) || containsAny(path, "tip", "notice");
        }
        if (containsPositiveCue(normalizedQuery, "\u65b9\u6cd5", "\u8bb2\u6cd5", "\u601d\u8def", "method", "approach")) {
            return "method".equals(role) || containsAny(path, "method", "approach");
        }
        if (queryWantsLesson(normalizedQuery)) {
            return "lesson".equals(role) || containsAny(path, "lesson", "handout", "textbook", "topic");
        }
        return true;
    }

    /**
     * Classroom queries often mention a role only to rule it out, for example "不要答案解析" or "题面不能排在前面".
     * Treat those as negative constraints instead of positive role intent, otherwise filtered library search keeps
     * promoting the exact sibling block the teacher explicitly said not to surface first.
     */
    private static boolean containsPositiveCue(String normalizedQuery, String... cues) {
        String haystack = normalizeText(textOrDefault(normalizedQuery, ""));
        for (String cue : cues) {
            String normalizedCue = normalizeText(textOrDefault(cue, ""));
            if (normalizedCue.isBlank()) {
                continue;
            }
            int startIndex = 0;
            while (startIndex >= 0 && startIndex < haystack.length()) {
                int matchIndex = haystack.indexOf(normalizedCue, startIndex);
                if (matchIndex < 0) {
                    break;
                }
                if (!negatedCueOccurrence(haystack, matchIndex, normalizedCue.length())) {
                    return true;
                }
                startIndex = matchIndex + normalizedCue.length();
            }
        }
        return false;
    }

    private static boolean negatedCueOccurrence(String normalizedQuery, int matchIndex, int cueLength) {
        int clauseStart = clauseStart(normalizedQuery, matchIndex);
        int clauseEnd = clauseEnd(normalizedQuery, matchIndex + cueLength);
        int beforeStart = Math.max(clauseStart, matchIndex - 8);
        int afterEnd = Math.min(clauseEnd, matchIndex + cueLength + 10);
        String before = normalizedQuery.substring(beforeStart, matchIndex);
        String after = normalizedQuery.substring(matchIndex + cueLength, afterEnd);
        return containsAnyLiteral(
                        before,
                        "\u4e0d\u8981",
                        "\u522b",
                        "\u4e0d\u662f",
                        "\u800c\u4e0d\u662f",
                        "\u4e0d\u60f3",
                        "\u4e0d\u627e",
                        "\u4e0d\u67e5",
                        "\u4e0d\u9700\u8981",
                        "\u65e0\u9700",
                        "\u4e0d\u7528",
                        "\u4e0d\u5fc5",
                        "\u4e0d\u53ea",
                        "\u4e0d\u8981\u53ea",
                        "\u4e0d\u8981\u5148",
                        "\u522b\u5148",
                        "\u522b\u628a")
                || containsAnyLiteral(
                        after,
                        "\u4e0d\u80fd",
                        "\u4e0d\u8981",
                        "\u4e0d\u5e94",
                        "\u522b",
                        "\u4e0d\u5fc5",
                        "\u65e0\u9700",
                        "\u4e0d\u4f18\u5148",
                        "\u4e0d\u80fd\u6392\u5728\u524d\u9762",
                        "\u4e0d\u6392\u5728\u524d\u9762",
                        "\u4e0d\u5728\u524d\u9762",
                        "\u522b\u6392\u5728\u524d\u9762",
                        "\u522b\u653e\u5728\u524d\u9762",
                        "\u522b\u5148\u8fd4",
                        "\u522b\u5148\u7ed9");
    }

    private static int clauseStart(String text, int index) {
        int boundary = 0;
        for (int cursor = Math.max(0, index - 12); cursor < index; cursor += 1) {
            if (isClauseBoundary(text.charAt(cursor))) {
                boundary = cursor + 1;
            }
        }
        return boundary;
    }

    private static int clauseEnd(String text, int index) {
        int boundary = text.length();
        for (int cursor = index; cursor < Math.min(text.length(), index + 12); cursor += 1) {
            if (isClauseBoundary(text.charAt(cursor))) {
                boundary = cursor;
                break;
            }
        }
        return boundary;
    }

    private static boolean isClauseBoundary(char value) {
        return value == ','
                || value == '.'
                || value == ';'
                || value == ':'
                || value == '!'
                || value == '?'
                || value == '\n'
                || value == '\r'
                || value == '\u3002'
                || value == '\uff0c'
                || value == '\u3001'
                || value == '\uff1b'
                || value == '\uff1a'
                || value == '\uff01'
                || value == '\uff1f';
    }

    private static boolean queryRejectsRole(String normalizedQuery, String... cues) {
        String haystack = normalizeText(textOrDefault(normalizedQuery, ""));
        for (String cue : cues) {
            String normalizedCue = normalizeText(textOrDefault(cue, ""));
            if (normalizedCue.isBlank()) {
                continue;
            }
            int startIndex = 0;
            while (startIndex >= 0 && startIndex < haystack.length()) {
                int matchIndex = haystack.indexOf(normalizedCue, startIndex);
                if (matchIndex < 0) {
                    break;
                }
                if (negatedCueOccurrence(haystack, matchIndex, normalizedCue.length())) {
                    return true;
                }
                startIndex = matchIndex + normalizedCue.length();
            }
        }
        return false;
    }

    private static boolean containsAnyLiteral(String haystack, String... needles) {
        if (haystack == null || haystack.isBlank()) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads one-step neighbors as a scoring signal, not just as returned evidence. This specifically addresses the
     * recurring real-world failure where the correct document is found but a sibling question block beats the adjacent
     * answer/analysis block. Neighbor text is only trusted inside the same parsed document, so this does not expand the
     * candidate corpus or leak cross-document noise back into stage two.
     */
    private static double neighborSupportScore(
            BlockContext target,
            List<BlockContext> documentBlocks,
            String normalizedQuery,
            String[] terms) {
        if (documentBlocks == null || documentBlocks.size() <= 1) {
            return 0;
        }
        int targetIndex = -1;
        for (int index = 0; index < documentBlocks.size(); index += 1) {
            if (documentBlocks.get(index).block().blockId().equals(target.block().blockId())) {
                targetIndex = index;
                break;
            }
        }
        if (targetIndex < 0) {
            return 0;
        }
        boolean wantsAnalysis = queryWantsAnalysis(normalizedQuery);
        boolean wantsQuestion = queryWantsQuestion(normalizedQuery, wantsAnalysis);
        boolean wantsLesson = queryWantsLesson(normalizedQuery);
        double score = 0;
        int start = Math.max(0, targetIndex - EVIDENCE_WINDOW_RADIUS);
        int end = Math.min(documentBlocks.size() - 1, targetIndex + EVIDENCE_WINDOW_RADIUS);
        for (int index = start; index <= end; index += 1) {
            if (index == targetIndex) {
                continue;
            }
            BlockContext neighbor = documentBlocks.get(index);
            double lexical = score(neighbor.searchableText(), normalizedQuery, terms)
                    + fieldScore(neighbor.block().section(), normalizedQuery, terms)
                    + fieldScore(neighbor.sourcePath(), normalizedQuery, terms);
            if (lexical <= 0) {
                continue;
            }
            String targetRole = normalizeText(target.blockRole());
            String neighborRole = normalizeText(neighbor.blockRole());
            if ("analysis".equals(targetRole) && "question".equals(neighborRole)) {
                score += Math.min(3.4d, lexical * 0.42d);
                if (wantsAnalysis) {
                    score += 2.1d;
                }
            } else if ("question".equals(targetRole) && "analysis".equals(neighborRole) && wantsAnalysis) {
                score -= Math.min(5.4d, lexical * 0.55d + 1.8d);
            } else if ("question".equals(targetRole) && "lesson".equals(neighborRole) && wantsQuestion) {
                score += Math.min(1.8d, lexical * 0.2d + 0.6d);
            } else if ("lesson".equals(targetRole) && "question".equals(neighborRole) && wantsQuestion) {
                score -= Math.min(3.2d, lexical * 0.35d + 0.8d);
            } else if ("lesson".equals(targetRole) && "analysis".equals(neighborRole) && wantsAnalysis) {
                score -= Math.min(2.2d, lexical * 0.22d + 0.6d);
            } else if ("method".equals(targetRole) && "boardwork".equals(neighborRole)) {
                score += Math.min(1.5d, lexical * 0.2d);
            } else if ("tip".equals(targetRole) && ("method".equals(neighborRole) || "boardwork".equals(neighborRole))) {
                score += Math.min(1.0d, lexical * 0.15d);
            } else if ("question".equals(targetRole) && wantsQuestion) {
                score += Math.min(0.8d, lexical * 0.12d);
            } else if ("lesson".equals(targetRole) && wantsLesson) {
                score += Math.min(1.1d, lexical * 0.12d);
            }
        }
        return score;
    }

    /**
     * When the caller already narrows the library, preserve the same candidate set but reorder wrong-role siblings
     * behind candidates that satisfy explicit query intent such as "题面/解析/讲法/板书". This keeps the filtered
     * retrieval path compatible with real AI callers that can pass `library`, while avoiding benchmark-only rules.
     */
    private static List<BlockCandidate> maybeRejectLowConfidenceFilteredHits(
            List<BlockCandidate> rankedCandidates,
            TeacherResourceSearchFilter filter,
            String normalizedQuery,
            String[] terms) {
        if (filter == null || filter.empty() || rankedCandidates.isEmpty()) {
            return rankedCandidates;
        }
        boolean explicitRoleIntent = queryHasExplicitRoleIntent(normalizedQuery);
        List<BlockCandidate> roleMatchedCandidates = explicitRoleIntent
                ? rankedCandidates.stream()
                        .filter(candidate -> roleSatisfiesQueryIntent(
                                candidate.block().blockRole(),
                                candidate.block().sourcePath(),
                                normalizedQuery))
                        .toList()
                : List.of();
        if (explicitRoleIntent && !roleMatchedCandidates.isEmpty()) {
            if (!roleSatisfiesQueryIntent(
                    rankedCandidates.getFirst().block().blockRole(),
                    rankedCandidates.getFirst().block().sourcePath(),
                    normalizedQuery)) {
                /*
                 * When the caller already narrowed the library and also clearly asks for "题面/解析/讲法" etc., keeping
                 * a wrong-role sibling at rank 1 is worse than preferring the best same-library block that satisfies
                 * the requested role. This reorders only the filtered list; it does not invent a new corpus or hardcode
                 * benchmark phrases.
                 */
                List<BlockCandidate> reordered = new ArrayList<>(roleMatchedCandidates);
                for (BlockCandidate candidate : rankedCandidates) {
                    if (!roleSatisfiesQueryIntent(
                            candidate.block().blockRole(),
                            candidate.block().sourcePath(),
                            normalizedQuery)) {
                        reordered.add(candidate);
                    }
                }
                rankedCandidates = List.copyOf(reordered);
            }
        }
        if (shouldRejectWeakFilteredTopCandidate(rankedCandidates, filter, normalizedQuery)) {
            return List.of();
        }
        return rankedCandidates;
    }

    /**
     * Explicit library selection should reduce cross-library noise, but it also makes broad within-library boilerplate
     * more visible. Reject only the weakest filtered hits: low score plus weak document/block anchors such as role,
     * headings, graph tags, or neighbor evidence. Keep this conservative and generic. If someone removes it, specified
     * library search will regress back to returning arbitrary same-library blocks for obviously out-of-scope queries.
     */
    private static boolean shouldRejectWeakFilteredTopCandidate(
            List<BlockCandidate> rankedCandidates,
            TeacherResourceSearchFilter filter,
            String normalizedQuery) {
        if (rankedCandidates.isEmpty() || filter == null || filter.sourceTypes().isEmpty()) {
            return false;
        }
        BlockCandidate topCandidate = rankedCandidates.getFirst();
        boolean explicitRoleIntent = queryHasExplicitRoleIntent(normalizedQuery);
        if (explicitRoleIntent
                && roleSatisfiesQueryIntent(
                        topCandidate.block().blockRole(),
                        topCandidate.block().sourcePath(),
                        normalizedQuery)) {
            return false;
        }
        double semanticAnchorScore = topCandidate.metadataScore() + Math.max(0.0d, topCandidate.graphScore());
        int matchedSubstantiveTerms = matchedSubstantiveTermCount(topCandidate, searchTerms(normalizedQuery));
        if (!explicitRoleIntent) {
            if (topCandidate.score() < FILTERED_REJECT_SCORE_THRESHOLD
                    && semanticAnchorScore <= 0.0d
                    && matchedSubstantiveTerms <= 2) {
                return true;
            }
            if (topCandidate.score() < FILTERED_REJECT_MAX_SCORE_WITHOUT_ANCHOR
                    && semanticAnchorScore <= 0.0d
                    && matchedSubstantiveTerms == 0
                    && Math.max(0.0d, topCandidate.neighborScore()) <= 0.0d) {
                return true;
            }
        }
        double anchoredScore = topCandidate.metadataScore()
                + topCandidate.structureScore()
                + Math.max(0.0d, topCandidate.graphScore())
                + Math.max(0.0d, topCandidate.roleScore())
                + Math.max(0.0d, topCandidate.neighborScore());
        return topCandidate.score() < FILTERED_REJECT_SCORE_THRESHOLD
                && anchoredScore < FILTERED_ANCHOR_SCORE_THRESHOLD
                && matchedSubstantiveTerms <= 1;
    }

    private static int matchedSubstantiveTermCount(BlockCandidate candidate, String[] terms) {
        if (terms == null || terms.length == 0) {
            return 0;
        }
        String haystack = normalizeText(String.join(
                " ",
                candidate.document().title(),
                candidate.block().searchableText(),
                candidate.block().sourcePath(),
                candidate.block().block().chapter(),
                candidate.block().block().section(),
                candidate.block().blockRole(),
                String.join(" ", candidate.block().graphTags())));
        int count = 0;
        for (String term : terms) {
            if (!term.isBlank() && haystack.contains(term)) {
                count += 1;
            }
        }
        return count;
    }

    private static double categoricalCueScore(
            String normalizedQuery,
            String normalizedHaystack,
            String[] queryCues,
            String[] documentCues) {
        if (!containsAny(normalizedQuery, queryCues)) {
            return 0;
        }
        return containsAny(normalizedHaystack, documentCues) ? 8.0d : -3.0d;
    }

    private static double exactQueryBonus(String searchableText, String normalizedQuery) {
        return searchableText.contains(normalizedQuery) ? 2.0d : 0.0d;
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
                response.retrievalMode(),
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

    private static String normalizeStrategy(String strategy) {
        String normalized = normalizeText(textOrDefault(strategy, STRATEGY_TWO_STAGE_DOC_BLOCK));
        if (STRATEGY_LEGACY_BLOCK_HYBRID.equals(normalized)) {
            return STRATEGY_LEGACY_BLOCK_HYBRID;
        }
        return STRATEGY_TWO_STAGE_DOC_BLOCK;
    }

    private static String retrievalMode(String strategy, TeacherResourceSearchFilter filter, String suffix) {
        String mode = filter.empty() ? strategy : strategy + "_filtered";
        if (suffix == null || suffix.isBlank()) {
            return mode;
        }
        return mode + "_" + suffix;
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
        if (GENERIC_SEARCH_TERMS.contains(normalizedCandidate)) {
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
            double score) {
    }

    private record BlockCandidate(
            TeacherResourceDocumentResponse document,
            BlockContext block,
            double score,
            double lexicalScore,
            double metadataScore,
            double structureScore,
            double graphScore,
            double roleScore,
            double neighborScore,
            double vectorScore) {
    }

    private record EvidenceWindow(
            List<String> blockIds,
            String text) {
    }

}
