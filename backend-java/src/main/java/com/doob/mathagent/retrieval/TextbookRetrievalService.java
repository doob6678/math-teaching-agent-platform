package com.doob.mathagent.retrieval;

import com.doob.mathagent.resources.TextbookCatalogItem;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunk;
import com.doob.mathagent.resources.TextbookChunkReader;
import com.doob.mathagent.resources.TextbookPageImageService;
import com.doob.mathagent.teacher.search.TeacherResourceGraphAlignmentService;
import com.doob.mathagent.vector.service.VectorIndexService;
import com.doob.mathagent.vector.service.VectorTextRerankResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.Comparator;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TextbookRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(TextbookRetrievalService.class);
    private static final long LOAD_FAILURE_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(30);
    /*
     * 任何可能改变教材命中结果的排序链路变更，都必须同步更新此版本号。
     * Redis 缓存键已包含语料签名、查询和数量；但候选准入或重排顺序调整后若仍沿用旧版本，
     * 服务重启后旧页面命中仍可能存活，导致在线评测比较的是过期结果而非当前代码结果。
     */
    /** 为保持审计与 API 兼容而保留的稳定公开策略标识。 */
    // 候选准入规则变化时必须变更缓存身份，否则 Redis 会静默返回小标题 BM25 加入前生成的旧响应。
    private static final String SEARCH_PIPELINE_VERSION = "two_stage_doc_page_v4_title_field_parent_rerank";
    /** 候选准入或校验语义变化时同步变更缓存结构版本。 */
    private static final String SEARCH_CACHE_SCHEMA_VERSION = "two_stage_section_block_reference_index";
    private static final Pattern QUERY_CLAUSE_SPLITTER = Pattern.compile("[\\r\\n,，。；;：:！？!?()（）\\[\\]【】]+");
    private final TextbookCatalogReader catalogReader;
    private final TextbookChunkReader chunkReader;
    private final LocalTextbookBm25SearchEngine searchEngine;
    private final RetrievalAuditSink auditSink;
    private final TextbookSearchCache searchCache;
    private final RedisTextbookSearchCacheProperties searchCacheProperties;
    private final TeacherResourceGraphAlignmentService graphAlignmentService;
    private final TextbookPageImageService pageImageService;
    private final TextbookPageImageSearchService pageImageSearchService;
    private final TextbookPageTextSearchService pageTextSearchService;
    private final VectorIndexService vectorIndexService;
    private final TextbookRetrievalProperties retrievalProperties;
    /**
     * 语料加载互斥锁：防止缓存未命中时多个请求同时读取大批量教材文件，降低缓存击穿风险。
     */
    private final Object corpusLoadLock = new Object();
    /**
     * 最近一次成功加载的教材语料快照：源文件临时异常时可作为旧缓存兜底，降低缓存雪崩影响。
     */
    private volatile CachedTextbookCorpus cachedCorpus;
    /**
     * 最近一次语料加载失败状态：用于失败冷却，避免底层文件或存储异常时每个请求都重复触发昂贵加载。
     */
    private volatile CorpusLoadFailure lastLoadFailure;

    @Autowired
    public TextbookRetrievalService(
            TextbookCatalogReader catalogReader,
            TextbookChunkReader chunkReader,
            LocalTextbookBm25SearchEngine searchEngine,
            RetrievalAuditSink auditSink,
            TextbookSearchCache searchCache,
            RedisTextbookSearchCacheProperties searchCacheProperties,
            TeacherResourceGraphAlignmentService graphAlignmentService,
            TextbookPageImageService pageImageService,
            TextbookPageImageSearchService pageImageSearchService,
            TextbookPageTextSearchService pageTextSearchService,
            VectorIndexService vectorIndexService,
            TextbookRetrievalProperties retrievalProperties) {
        this.catalogReader = Objects.requireNonNull(catalogReader, "catalogReader is required");
        this.chunkReader = Objects.requireNonNull(chunkReader, "chunkReader is required");
        this.searchEngine = Objects.requireNonNull(searchEngine, "searchEngine is required");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink is required");
        this.searchCache = Objects.requireNonNull(searchCache, "searchCache is required");
        this.searchCacheProperties = Objects.requireNonNull(searchCacheProperties, "searchCacheProperties is required");
        this.graphAlignmentService = Objects.requireNonNull(graphAlignmentService, "graphAlignmentService is required");
        this.pageImageService = Objects.requireNonNull(pageImageService, "pageImageService is required");
        this.pageImageSearchService = pageImageSearchService;
        this.pageTextSearchService = pageTextSearchService;
        this.vectorIndexService = Objects.requireNonNull(vectorIndexService, "vectorIndexService is required");
        this.retrievalProperties = Objects.requireNonNull(retrievalProperties, "retrievalProperties is required");
    }

    /**
     * Compatibility constructor for direct callers. Spring uses the configuration-aware constructor above; tests and
     * small maintenance tools retain a deterministic project default without duplicating hidden service constants.
     */
    public TextbookRetrievalService(
            TextbookCatalogReader catalogReader,
            TextbookChunkReader chunkReader,
            LocalTextbookBm25SearchEngine searchEngine,
            RetrievalAuditSink auditSink,
            TextbookSearchCache searchCache,
            RedisTextbookSearchCacheProperties searchCacheProperties,
            TeacherResourceGraphAlignmentService graphAlignmentService,
            TextbookPageImageService pageImageService,
            VectorIndexService vectorIndexService) {
        this(
                catalogReader,
                chunkReader,
                searchEngine,
                auditSink,
                searchCache,
                searchCacheProperties,
                graphAlignmentService,
                pageImageService,
                null,
                null,
                vectorIndexService,
                TextbookRetrievalProperties.defaults());
    }

    /**
     * 使用默认教材检索上下文执行搜索，主要供单元测试和内部调用使用。
     */
    public TextbookSearchResponse search(Path processedBooksRoot, TextbookSearchRequest request) {
        return search(processedBooksRoot, request, RetrievalRequestContext.defaultTextbookSearch());
    }

    /**
     * 执行 BM25-first 教材检索，并同步写入检索审计事件。
     */
    public TextbookSearchResponse search(
            Path processedBooksRoot,
            TextbookSearchRequest request,
            RetrievalRequestContext requestContext) {
        RetrievalRequestContext normalizedContext = requestContext == null
                ? RetrievalRequestContext.defaultTextbookSearch().normalize()
                : requestContext.normalize();
        String queryId = UUID.randomUUID().toString();
        long startedAtNanos = System.nanoTime();
        Path normalizedRoot = processedBooksRoot.toAbsolutePath().normalize();
        CachedTextbookCorpus corpus = loadCorpus(normalizedRoot);
        String semanticQuery = request.semanticQuery();
        // 教材证据属于固定公共语料。独立全库消融表明，向教材检索注入一跳教师资料图谱扩展会降低
        // 逻辑小标题的召回率，因此该路径刻意不使用图谱；共享图谱服务仍可供教师资料检索使用。
        TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph =
                TeacherResourceGraphAlignmentService.QueryGraphContext.EMPTY;
        String focusedQuery = focusedTextbookQuery(semanticQuery, queryGraph);
        List<TextbookChunk> scopedChunks = scopedChunks(corpus.chunks(), request.documentIds());
        LogicalBlockIndex scopedBlockIndex = request.documentIds().isEmpty()
                ? corpus.logicalBlockIndex()
                : logicalSectionIndex(scopedChunks);
        boolean cacheable = !request.hasFormulaImage();
        String cacheKey = searchCacheKey(corpus, request.limit(), focusedQuery, request.documentIds(), request.mode());
        TextbookSearchCache.CachedTextbookSearch cached = cacheable ? searchCache.find(cacheKey).orElse(null) : null;
        List<TextbookRetrievalStage> executionStages = new ArrayList<>();
        List<TextbookSearchHit> hits = cached == null
                ? rerankedHits(focusedQuery, request.limit(), scopedChunks, scopedBlockIndex, queryGraph, request, executionStages)
                : cached.hits();
        hits = attachControlledPageImageUris(hits);
        if (cacheable && cached == null) {
            searchCache.put(
                    cacheKey,
                    new TextbookSearchCache.CachedTextbookSearch(
                            focusedQuery,
                            request.limit(),
                            SEARCH_PIPELINE_VERSION,
                            hits.size(),
                            hits),
                    hits.isEmpty()
                            ? searchCacheProperties.normalizedNullValueTtl()
                            : searchCacheProperties.normalizedTtl());
        }
        int elapsedMs = elapsedMs(startedAtNanos);
        TextbookSearchResponse response = new TextbookSearchResponse(
                queryId,
                request.query(),
                request.limit(),
                cached == null ? SEARCH_PIPELINE_VERSION : "redis_cache_" + SEARCH_PIPELINE_VERSION,
                retrievalDescription(request.mode(), scopedChunks, hits, cached != null),
                retrievalStages(request.mode(), scopedChunks, hits, cached != null, executionStages),
                hits.size(),
                hits);
        auditSink.record(RetrievalAuditEvent.from(queryId, request, response, elapsedMs, normalizedContext));
        return response;
    }

    /**
     * Agent 组装的教材查询常会混入资料库提示、证据要求等路由/控制语句。若把整句直接送入教材 BM25，
     * 会在大量页面中产生泛化 n-gram 命中，并让语义重排浪费在噪声候选集上。
     *
     * <p>该聚焦器保持与具体语料无关：不会删除某个评测集专用短语，也不会硬编码教材知识点。
     * 当图谱对齐可用时优先选择匹配规范化标签的分句；否则退化为最长的有效内容分句。</p>
     */
    private String focusedTextbookQuery(
            String rawQuery,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
        String normalized = normalizeQueryText(rawQuery);
        if (normalized.isBlank()) {
            return normalized;
        }
        List<String> clauses = splitQueryClauses(normalized);
        List<String> primaryTags = normalizeQueryParts(queryGraph == null ? List.of() : queryGraph.primaryTagNames());
        List<String> expandedTags = normalizeQueryParts(queryGraph == null ? List.of() : queryGraph.expandedTagNames());

        LinkedHashSet<String> focusedParts = new LinkedHashSet<>();
        appendMatchingClauses(focusedParts, clauses, primaryTags);
        appendMatchingClauses(focusedParts, clauses, expandedTags);
        // Graph-aligned clauses already carry the subject intent. Filling the remaining budget with unrelated routing
        // clauses such as library or role instructions pollutes both BM25 and BGE. Longest-clause fallback is only for
        // queries where graph alignment supplied no usable clause at all.
        if (focusedParts.isEmpty()) {
            appendLongestRemainingClauses(focusedParts, clauses, retrievalProperties.queryFocus().maxClauses());
        }
        primaryTags.stream().limit(retrievalProperties.queryFocus().maxGraphTags()).forEach(focusedParts::add);
        if (focusedParts.size() < retrievalProperties.queryFocus().maxClauses() + 1) {
            expandedTags.stream()
                    .filter(tag -> focusedParts.stream().noneMatch(existing -> compact(existing).contains(compact(tag))))
                    .limit(retrievalProperties.queryFocus().maxGraphTags())
                    .forEach(focusedParts::add);
        }
        String focused = truncateForRerank(String.join(" ", focusedParts), retrievalProperties.queryFocus().maxQueryChars());
        return focused.isBlank() ? normalized : focused;
    }

    private static List<String> splitQueryClauses(String query) {
        LinkedHashSet<String> clauses = new LinkedHashSet<>();
        for (String part : QUERY_CLAUSE_SPLITTER.split(query)) {
            String normalized = normalizeQueryText(part);
            if (!normalized.isBlank()) {
                clauses.add(normalized);
            }
        }
        if (clauses.isEmpty()) {
            clauses.add(query);
        }
        return List.copyOf(clauses);
    }

    private static List<String> normalizeQueryParts(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String cleaned = normalizeQueryText(value);
            if (!cleaned.isBlank()) {
                normalized.add(cleaned);
            }
        }
        return List.copyOf(normalized);
    }

    private void appendMatchingClauses(
            LinkedHashSet<String> focusedParts,
            List<String> clauses,
            List<String> normalizedTags) {
        if (focusedParts.size() >= retrievalProperties.queryFocus().maxClauses() || normalizedTags.isEmpty()) {
            return;
        }
        for (String clause : clauses) {
            if (focusedParts.size() >= retrievalProperties.queryFocus().maxClauses()) {
                return;
            }
            boolean matched = normalizedTags.stream().anyMatch(tag -> containsNormalized(clause, tag));
            if (matched) {
                focusedParts.add(clause);
            }
        }
    }

    /**
     * Keep at least one long non-routing clause even when graph tags already matched another clause.
     *
     * <p>Textbook live misses showed a failure mode where the focus builder kept only chapter/topic clauses such as
     * "第六章导数及其应用" and then dropped the later evidence-bearing phrase that actually distinguishes one page from
     * its siblings. The result looked semantically clean, but stage one only saw generic chapter language and admitted
     * the wrong nearby pages. This helper keeps the graph-aligned clause and then fills the remaining clause budget
     * with the longest unseen user clause so the reranker still receives the page-discriminating phrase.</p>
     */
    private static void appendLongestRemainingClauses(
            LinkedHashSet<String> focusedParts,
            List<String> clauses,
            int maxClauses) {
        if (focusedParts.size() >= maxClauses) {
            return;
        }
        clauses.stream()
                .filter(clause -> !focusedParts.contains(clause))
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo))
                .limit(Math.max(0, maxClauses - focusedParts.size()))
                .forEach(focusedParts::add);
    }

    private static boolean containsNormalized(String haystack, String needle) {
        String compactHaystack = compact(haystack);
        String compactNeedle = compact(needle);
        return !compactNeedle.isBlank() && compactHaystack.contains(compactNeedle);
    }

    private static String normalizeQueryText(String value) {
        return String.valueOf(value == null ? "" : value)
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String compact(String value) {
        return normalizeQueryText(value).replace(" ", "").toLowerCase(Locale.ROOT);
    }

    /** Removes only a printed-page suffix attached to a visible Chinese heading. */
    static String visibleSectionTitle(String value) {
        return compact(value).replaceFirst("(?<=[\\p{IsHan}])\\d{1,3}$", "");
    }

    /**
     * The lexical engine is only the coarse-recall stage for textbooks. It may still use BM25/metadata signals to
     * avoid scanning unrelated pages, but it no longer decides the final rank returned to callers.
     *
     * <p>Keep the pipeline aligned with the intended two-stage shape:</p>
     *
     * <ol>
     *     <li>Document/page candidate admission stays cheap and wide enough, using lexical recall plus lightweight
     *     embedding support.</li>
     *     <li>Only the final page-level competition uses the real rerank model.</li>
     * </ol>
     *
     * <p>The previous implementation reranked once at document level and again at page level. On the local BGE
     * reranker that doubled the most expensive worker call and made cold textbook queries time out. We therefore keep
     * semantic retrieval at document level, but reserve the cross-encoder budget for the final page/block decision,
     * which is the stage that actually fixes sibling-page confusion.</p>
     */
    private List<TextbookSearchHit> rerankedHits(
            String query,
            int limit,
            List<TextbookChunk> chunks,
            LogicalBlockIndex blockIndex,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph,
            TextbookSearchRequest request,
            List<TextbookRetrievalStage> executionStages) {
        int safeLimit = Math.max(1, limit);
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        /*
         * Request the full positive lexical candidate set instead of locking quality to a multiplier/floor constant.
         * The lexical engine now only decides which pages are plausible enough to enter semantic rerank, not their
         * final order.
         */
        List<TextbookSearchHit> coarseHits = collapseLogicalBlockCandidates(
                searchEngine.search(query, chunks, chunks.size(), queryGraph), blockIndex);
        Map<String, List<TextbookSearchHit>> lexicalCandidates = groupByDocument(coarseHits);
        // Fielded title BM25 remains a separate candidate route. It prevents body
        // OCR frequency from suppressing an exact visible small heading, without
        // copying c2 body text or adding incomparable route scores together.
        List<TextbookSearchHit> titleHits = collapseLogicalBlockCandidates(
                searchEngine.searchSectionTitles(query, chunks, chunks.size()), blockIndex);
        Map<String, List<TextbookSearchHit>> titleCandidates = groupByDocument(titleHits);
        executionStages.add(new TextbookRetrievalStage(
                "title_bm25",
                "小标题 BM25 召回",
                "completed",
                "已独立检索可见小标题；该路线只提供候选，不与正文 BM25 或 BGE 分数相加。"));
        /*
         * Stage one intentionally unions independent evidence routes. A positive BM25 hit must not suppress BGE:
         * OCR wording often overlaps a generic sibling page while BGE retrieves the semantically correct page. The
         * subsequent real document/page rerank resolves this bounded union; we never add incomparable lexical and
         * vector scores together.
         */
        Map<String, List<TextbookSearchHit>> semanticCandidates = semanticPageDocumentCandidates(
                query,
                blockIndex,
                request,
                executionStages);
        Map<String, List<TextbookSearchHit>> topLexicalCandidates = topLexicalDocumentCandidates(lexicalCandidates, safeLimit);
        Map<String, List<TextbookSearchHit>> topTitleCandidates = topLexicalDocumentCandidates(titleCandidates, safeLimit);
        Map<String, List<TextbookSearchHit>> coarseDocumentCandidates = mergeDocumentCandidates(
                topLexicalCandidates,
                topTitleCandidates,
                semanticCandidates);
        if (coarseDocumentCandidates.isEmpty()) {
            return List.of();
        }
        Map<String, List<TextbookSearchHit>> supportHitsByDocId = cappedSupportHitsByDocId(
                coarseDocumentCandidates,
                topLexicalCandidates,
                topTitleCandidates,
                semanticCandidates);
        /*
         * This is deliberately not a second cross-encoder pass. Stage one is document-level coarse recall: the BGE
         * page index contributes semantic document order and BM25 contributes independent lexical admission. Applying
         * the costly cross-encoder here and again at page level duplicates work and breaks the 2.2 second online
         * budget without increasing the in-document evidence precision that stage two is meant to solve.
         */
        List<String> rankedDocIds = interleaveDocumentIds(List.of(
                new ArrayList<>(topLexicalCandidates.keySet()),
                new ArrayList<>(topTitleCandidates.keySet()),
                new ArrayList<>(semanticCandidates.keySet())),
                retrievalProperties.rerank().maxRerankDocuments());
        List<TextbookSearchHit> pageCandidates = pageCandidates(rankedDocIds, supportHitsByDocId, safeLimit);
        Map<String, String> pageTexts = pageCandidateTexts(query, pageCandidates, blockIndex);
        Map<String, Double> pageSemanticScores = semanticScoreByKey(query, pageTexts, executionStages);
        return pageCandidates.stream()
                .map(hit -> new TextbookPageCandidate(
                        hit,
                        pageSemanticScores.getOrDefault(hit.chunkId(), hit.score())))
                // Cross-encoder logits are used only to order admitted evidence. They are model-dependent and this
                // service has no calibrated relevance threshold, so the response must not claim negative filtering.
                .sorted(Comparator.<TextbookPageCandidate>comparingDouble(TextbookPageCandidate::pageSemanticScore).reversed()
                        .thenComparing(Comparator.comparingDouble((TextbookPageCandidate candidate) -> candidate.hit().score()).reversed())
                        .thenComparing(candidate -> candidate.hit().docId())
                        .thenComparingInt(candidate -> candidate.hit().pageNo()))
                .limit(safeLimit)
                .map(candidate -> withScore(candidate.hit(), candidate.pageSemanticScore()))
                .toList();
    }

    /**
     * Adds a true semantic coarse-recall route before document rerank.
     *
     * <p>The preferred route is the worker's BGE page-text index. It searches all real textbook pages without relying
     * on literal overlap, while preserving BGE rerank as the final authority for evidence order. CLIP remains the
     * second route for pages whose textual parse is weak or absent; it is never used as a replacement for mathematical
     * text semantics.</p>
     */
    private Map<String, List<TextbookSearchHit>> semanticPageDocumentCandidates(
            String query,
            LogicalBlockIndex blockIndex,
            TextbookSearchRequest request,
            List<TextbookRetrievalStage> executionStages) {
        if (blockIndex == null || blockIndex.membersByBlockKey().isEmpty()) {
            return Map.of();
        }
        int semanticPageLimit = Math.multiplyExact(
                retrievalProperties.rerank().maxDocumentCandidates(),
                retrievalProperties.rerank().maxPagesPerDocument());
        Map<String, List<TextbookSearchHit>> mergedCandidates = new LinkedHashMap<>();
        TextbookRetrievalMode mode = request.mode();
        if (mode.usesTextPageIndex() && pageTextSearchService != null && !query.isBlank()) {
            long startedAt = System.nanoTime();
            try {
                TextbookPageTextSearchResponse response = pageTextSearchService.search(
                        new TextbookPageTextSearchRequest(query, semanticPageLimit, request.documentIds()));
                Map<String, List<TextbookSearchHit>> bgeCandidates = new LinkedHashMap<>();
                for (TextbookPageTextSearchHit textHit : response.hits()) {
                    for (TextbookChunk chunk : matchingLogicalBlockRepresentatives(textHit, blockIndex)) {
                        bgeCandidates.computeIfAbsent(chunk.docId(), ignored -> new ArrayList<>())
                                .add(bgeCoarseHit(chunk, textHit));
                    }
                }
                appendCandidateDocuments(mergedCandidates, bgeCandidates);
                executionStages.add(new TextbookRetrievalStage(
                        "bge_page", "BGE 文本页召回", "completed", "已查询教材文本向量索引并返回 " + response.hits().size() + " 个候选页。", elapsedMs(startedAt)));
            } catch (RuntimeException exception) {
                // A missing/rebuilding BGE index must not remove the independently maintained CLIP evidence route.
                log.warn("textbook_bge_semantic_coarse_recall_unavailable message={}", exception.getMessage());
                executionStages.add(new TextbookRetrievalStage(
                        "bge_page", "BGE 文本页召回", "unavailable", "BGE 文本页索引暂不可用，已转入可用的其他检索路径。", elapsedMs(startedAt)));
            }
        }
        if (!mode.usesClipPageIndex() || pageImageSearchService == null) {
            return mergedCandidates;
        }
        try {
            long startedAt = System.nanoTime();
            TextbookPageImageSearchResponse response = pageImageSearchService.search(
                    new TextbookPageImageSearchRequest(query, request.formulaImage(), semanticPageLimit, request.documentIds()));
            if (response.hits() == null || response.hits().isEmpty()) {
                return mergedCandidates;
            }
            Map<String, List<TextbookSearchHit>> candidates = new LinkedHashMap<>();
            for (TextbookPageImageSearchHit imageHit : response.hits()) {
                for (String blockKey : blockIndex.blockKeysByDocumentPage().getOrDefault(
                        imageHit.docId() + "#" + imageHit.pageNo(), List.of())) {
                    TextbookChunk chunk = blockIndex.representativeByBlockKey().get(blockKey);
                    if (chunk == null) {
                        continue;
                    }
                    candidates.computeIfAbsent(chunk.docId(), ignored -> new ArrayList<>())
                            .add(clipCoarseHit(chunk, imageHit));
                }
            }
            appendCandidateDocuments(mergedCandidates, candidates);
            executionStages.add(new TextbookRetrievalStage(
                    "clip_page", "CLIP 页面图像召回", "completed", "已查询教材页面图像索引并返回 " + response.hits().size() + " 个候选页。", elapsedMs(startedAt)));
            return mergedCandidates;
        } catch (RuntimeException exception) {
            // Text retrieval stays available when the optional worker page index is temporarily unavailable.
            log.warn("textbook_clip_semantic_coarse_recall_unavailable message={}", exception.getMessage());
            executionStages.add(new TextbookRetrievalStage(
                    "clip_page", "CLIP 页面图像召回", "unavailable", "CLIP 页面图像索引暂不可用，未把它伪装成命中。", -1L));
            return mergedCandidates;
        }
    }

    /**
     * Maps a worker page hit to the logical small-heading block that owns that page.
     *
     * <p>The worker index remains page-addressable, but page number is evidence location rather than the retrieval
     * unit. Source chunk identity is preferred; page and section metadata are only fallbacks. A legacy section id can
     * cover several visible headings, so title equality prevents those headings from being merged.</p>
     */
    private static List<TextbookChunk> matchingLogicalBlockRepresentatives(
            TextbookPageTextSearchHit hit,
            LogicalBlockIndex index) {
        if (hit == null || index == null) {
            return List.of();
        }
        TextbookChunk directChild = firstOriginalChild(index, hit.sourceChunkId(), hit.chunkId());
        if (directChild != null) {
            return List.of(directChild);
        }
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        String pageKey = hit.docId() + "#" + hit.pageNo();
        appendMatchingLogicalBlockKeys(
                selected,
                index.blockKeysByDocumentPage().getOrDefault(pageKey, List.of()),
                hit.sectionId(),
                hit.sectionTitle(),
                index);
        if (!selected.isEmpty()) {
            return logicalBlockRepresentatives(selected, index, hit.pageNo());
        }
        String sectionKey = hit.docId() + "#" + textOrBlank(hit.sectionId());
        appendMatchingLogicalBlockKeys(
                selected,
                index.blockKeysByDocumentSection().getOrDefault(sectionKey, List.of()),
                hit.sectionId(),
                hit.sectionTitle(),
                index);
        return logicalBlockRepresentatives(selected, index, hit.pageNo());
    }

    /** Resolves an exact worker child identity before using page/section fallback metadata. */
    private static TextbookChunk firstOriginalChild(
            LogicalBlockIndex index,
            String firstChunkId,
            String secondChunkId) {
        TextbookChunk first = firstChunkId == null || firstChunkId.isBlank()
                ? null
                : index.originalByChunkId().get(firstChunkId);
        if (first != null) {
            return first;
        }
        return secondChunkId == null || secondChunkId.isBlank()
                ? null
                : index.originalByChunkId().get(secondChunkId);
    }

    /** Keeps only the worker hit's visible heading when a legacy section id is shared. */
    private static void appendMatchingLogicalBlockKeys(
            LinkedHashSet<String> selected,
            List<String> candidateBlockKeys,
            String sectionId,
            String sectionTitle,
            LogicalBlockIndex index) {
        String expectedTitle = visibleSectionTitle(sectionTitle);
        for (String blockKey : candidateBlockKeys == null ? List.<String>of() : candidateBlockKeys) {
            TextbookChunk candidate = index.representativeByBlockKey().get(blockKey);
            if (candidate == null) {
                continue;
            }
            boolean sectionMatches = sectionId == null
                    || sectionId.isBlank()
                    || sectionId.equals(candidate.sectionId());
            boolean titleMatches = expectedTitle.isBlank()
                    || expectedTitle.equals(visibleSectionTitle(candidate.sectionTitle()));
            if (sectionMatches && titleMatches) {
                selected.add(blockKey);
            }
        }
    }

    /** Resolves index keys to physical representatives without constructing a second text corpus. */
    private static List<TextbookChunk> logicalBlockRepresentatives(
            LinkedHashSet<String> blockKeys,
            LogicalBlockIndex index,
            int preferredPageNo) {
        return blockKeys.stream()
                .map(blockKey -> preferredPageChildOrRepresentative(blockKey, index, preferredPageNo))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(TextbookRetrievalService::evidenceLength)
                        .reversed()
                        .thenComparing(chunk -> textOrBlank(chunk.chunkId())))
                .toList();
    }

    /** Uses the page that produced a legacy worker hit when the child id is unavailable. */
    private static TextbookChunk preferredPageChildOrRepresentative(
            String blockKey,
            LogicalBlockIndex index,
            int preferredPageNo) {
        return index.membersByBlockKey().getOrDefault(blockKey, List.of()).stream()
                .filter(chunk -> chunk.pageNo() == preferredPageNo)
                .max(Comparator.comparingInt(TextbookRetrievalService::evidenceLength)
                        .thenComparing(chunk -> textOrBlank(chunk.chunkId())))
                .orElse(index.representativeByBlockKey().get(blockKey));
    }

    /**
     * Resolves worker evidence by its stable section identity first.  The page key
     * is only a legacy fallback; returning every block on that page prevents a
     * multi-section page from silently losing all but the first chunk.
     */
    private static List<TextbookChunk> matchingChunks(
            String chunkId,
            String sectionId,
            String pageKey,
            Map<String, TextbookChunk> chunkById,
            Map<String, List<TextbookChunk>> chunksBySectionId,
            Map<String, List<TextbookChunk>> chunksByDocumentPage) {
        if (sectionId != null && !sectionId.isBlank()) {
            String sectionKey = pageKey.substring(0, pageKey.indexOf('#') + 1) + sectionId;
            List<TextbookChunk> sectionChunks = samePageSectionChunks(
                    chunksBySectionId.get(sectionKey),
                    pageNoFromKey(pageKey));
            if (!sectionChunks.isEmpty()) {
                return evidenceOrderedChunks(sectionChunks);
            }
        }
        if (chunkId != null && !chunkId.isBlank()) {
            TextbookChunk exact = chunkById.get(chunkId);
            if (exact != null) {
                return List.of(exact);
            }
        }
        return evidenceOrderedChunks(chunksByDocumentPage.getOrDefault(pageKey, List.of()));
    }

    /**
     * A section often has a short OCR heading, a figure caption, and the real
     * prose/definition block.  Worker BGE may identify the shared section id
     * through the heading; order its sibling chunks by actual evidence length
     * so the bounded rerank window receives the explanatory block first.
     *
     * <p>This is query-independent structural ordering. It neither changes a
     * stage-one score nor selects a mathematical topic; it only prevents an
     * empty heading from consuming the one candidate slot for its own section.</p>
     */
    private static List<TextbookChunk> evidenceOrderedChunks(List<TextbookChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        return chunks.stream()
                .sorted(Comparator.comparingInt(TextbookRetrievalService::evidenceLength)
                        .reversed()
                        .thenComparing(chunk -> textOrBlank(chunk.chunkId())))
                .toList();
    }

    /**
     * Restricts a worker page hit to sibling blocks that are physically present on that same page.
     * A stable section id may span continuation pages, but a page-level embedding score is evidence
     * only for the page that produced it; other pages must earn their own stage-one hit.
     */
    static List<TextbookChunk> samePageSectionChunks(List<TextbookChunk> chunks, int pageNo) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        return chunks.stream().filter(chunk -> chunk.pageNo() == pageNo).toList();
    }

    /** Extracts the numeric page component from the internal {@code docId#pageNo} key. */
    private static int pageNoFromKey(String pageKey) {
        if (pageKey == null) {
            return -1;
        }
        int separator = pageKey.lastIndexOf('#');
        if (separator < 0 || separator + 1 >= pageKey.length()) {
            return -1;
        }
        try {
            return Integer.parseInt(pageKey.substring(separator + 1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /** Measures visible text evidence without inventing a relevance score. */
    private static int evidenceLength(TextbookChunk chunk) {
        if (chunk == null) {
            return 0;
        }
        return textOrBlank(chunk.text()).length() + textOrBlank(chunk.formulaText()).length();
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : (fallback == null ? "" : fallback);
    }

    private static Map<String, List<TextbookSearchHit>> groupByDocument(List<TextbookSearchHit> hits) {
        Map<String, List<TextbookSearchHit>> grouped = new LinkedHashMap<>();
        if (hits != null) {
            for (TextbookSearchHit hit : hits) {
                grouped.computeIfAbsent(hit.docId(), ignored -> new ArrayList<>()).add(hit);
            }
        }
        return grouped;
    }

    /**
     * 候选召回后按父级逻辑块折叠 sibling child，避免同一小标题的多个片段占满结果列表。
     * 最终保留得分最高的真实 child，因此引用仍可准确落到原始页码和证据片段。
     *
     * <p>首个证明该逻辑块的候选路线分数会被保留。返回命中仅替换为该逻辑块中最强的原始来源记录，
     * 用于展示和构建最终重排负载；不会创建合成的 TextbookChunk，也不会持久化拼接后的文本。</p>
     */
    private static List<TextbookSearchHit> collapseLogicalBlockCandidates(
            List<TextbookSearchHit> hits,
            LogicalBlockIndex index) {
        if (hits == null || hits.isEmpty() || index == null) {
            return hits == null ? List.of() : hits;
        }
        Map<String, TextbookSearchHit> bestByBlock = new LinkedHashMap<>();
        for (TextbookSearchHit hit : hits) {
            // blockKey 代表父级逻辑块；优先按原始 child ID 查找，兼容历史检索结果再回退到 section 元数据。
            String blockKey = blockKeyForHit(hit, index);
            if (blockKey == null || blockKey.isBlank()) {
                blockKey = firstNonBlank(hit.docId(), "unknown-document") + "#" + firstNonBlank(hit.chunkId(), "unknown-chunk");
            }
            // Keep the child that actually earned route-local recall. The block key
            // deduplicates siblings and supplies transient parent context to rerank,
            // but replacing this hit with the longest sibling loses the source page.
            TextbookSearchHit candidate = hit;
            TextbookSearchHit current = bestByBlock.get(blockKey);
            if (current == null || candidate.score() > current.score()) {
                bestByBlock.put(blockKey, candidate);
            }
        }
        return bestByBlock.values().stream()
                .sorted(Comparator.comparingDouble(TextbookSearchHit::score).reversed()
                        .thenComparing(TextbookSearchHit::docId)
                        .thenComparing(TextbookSearchHit::chunkId))
                .toList();
    }

    /** Finds a logical block from an original chunk id first, then from section metadata for legacy route responses. */
    private static String blockKeyForHit(TextbookSearchHit hit, LogicalBlockIndex index) {
        if (hit == null || index == null) {
            return null;
        }
        String direct = index.blockKeyByOriginalChunkId().get(hit.chunkId());
        if (direct != null) {
            return direct;
        }
        String sectionKey = firstNonBlank(hit.docId(), "") + "#" + firstNonBlank(hit.sectionId(), "");
        String expectedTitle = visibleSectionTitle(hit.sectionTitle());
        for (String key : index.blockKeysByDocumentSection().getOrDefault(sectionKey, List.of())) {
            TextbookChunk representative = index.representativeByBlockKey().get(key);
            if (representative != null && (expectedTitle.isBlank()
                    || expectedTitle.equals(visibleSectionTitle(representative.sectionTitle())))) {
                return key;
            }
        }
        return null;
    }


    /**
     * Preserves source-local candidate order while taking the document union from lexical and BGE/CLIP recall.
     *
     * <p>Scores from BM25, BGE, and CLIP are different quantities, so combining them numerically would introduce an
     * arbitrary hidden weight. This method only admits evidence; the following cross-encoder stage is the first place
     * where candidates compete for rank.</p>
     */
    @SafeVarargs
    private static Map<String, List<TextbookSearchHit>> mergeDocumentCandidates(
            Map<String, List<TextbookSearchHit>>... candidateRoutes) {
        Map<String, List<TextbookSearchHit>> merged = new LinkedHashMap<>();
        // Keep lexical document admission stable while retaining semantic-only
        // documents and pages as independent rescue evidence. The raw scores are
        // still never combined; this is only an admission order.
        for (Map<String, List<TextbookSearchHit>> route : candidateRoutes) {
            appendCandidateDocuments(merged, route);
        }
        return merged;
    }

    private static void appendCandidateDocuments(
            Map<String, List<TextbookSearchHit>> target,
            Map<String, List<TextbookSearchHit>> source) {
        if (source == null) {
            return;
        }
        source.forEach((docId, hits) -> {
            if (docId == null || docId.isBlank() || hits == null || hits.isEmpty()) {
                return;
            }
            List<TextbookSearchHit> existing = target.computeIfAbsent(docId, ignored -> new ArrayList<>());
            LinkedHashSet<String> seenChunks = existing.stream()
                    .map(TextbookSearchHit::chunkId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            hits.stream().filter(hit -> seenChunks.add(hit.chunkId())).forEach(existing::add);
        });
    }

    private static TextbookSearchHit clipCoarseHit(TextbookChunk chunk, TextbookPageImageSearchHit imageHit) {
        return new TextbookSearchHit(
                chunk.chunkId(),
                chunk.sectionId() == null || chunk.sectionId().isBlank() ? chunk.chunkId() : chunk.sectionId(),
                imageHit.score(),
                "clip_semantic_coarse",
                chunk.docId(),
                textOrFallback(chunk.bookName(), imageHit.bookName()),
                chunk.volume(),
                chunk.chapterPath() == null ? List.of() : chunk.chapterPath(),
                chunk.pageNo(),
                chunk.printedPageNo(),
                textOrFallback(chunk.sectionTitle(), imageHit.sectionTitle()),
                textOrFallback(chunk.text(), imageHit.text()),
                chunk.formulaText(),
                chunk.sourcePageImage(),
                "clip_semantic_page",
                imageHit.imageUri());
    }

    /** Converts BGE page-index metadata back to the source chunk so stage two always reranks the parsed evidence. */
    private static TextbookSearchHit bgeCoarseHit(TextbookChunk chunk, TextbookPageTextSearchHit textHit) {
        return new TextbookSearchHit(
                chunk.chunkId(),
                firstNonBlank(chunk.sectionId(), textHit.sectionId()),
                textHit.score(),
                "bge_semantic_coarse",
                chunk.docId(),
                textOrFallback(chunk.bookName(), textHit.bookName()),
                chunk.volume(),
                chunk.chapterPath() == null ? List.of() : chunk.chapterPath(),
                chunk.pageNo(),
                chunk.printedPageNo(),
                textOrFallback(chunk.sectionTitle(), textHit.sectionTitle()),
                textOrFallback(chunk.text(), textHit.text()),
                chunk.formulaText(),
                chunk.sourcePageImage(),
                "bge_semantic_page",
                textHit.imageUri());
    }

    private static String textOrFallback(String primary, String fallback) {
        return primary == null || primary.isBlank() ? (fallback == null ? "" : fallback) : primary;
    }

    /**
     * Document-level coarse retrieval already produced a global lexical ordering. Keep only the strongest few book
     * buckets before the final page rerank; otherwise one top-5 page query drags many irrelevant books into the slow
     * cross-encoder stage even though only a handful can contribute final winners.
     */
    private Map<String, List<TextbookSearchHit>> topLexicalDocumentCandidates(
            Map<String, List<TextbookSearchHit>> hitsByDocId,
            int safeLimit) {
        int docLimit = Math.max(1, Math.min(retrievalProperties.rerank().maxDocumentCandidates(), safeLimit));
        Map<String, List<TextbookSearchHit>> candidates = new LinkedHashMap<>();
        hitsByDocId.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, List<TextbookSearchHit>>>comparingDouble(
                                entry -> entry.getValue().isEmpty() ? 0.0d : entry.getValue().getFirst().score())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(docLimit)
                .forEach(entry -> candidates.put(entry.getKey(), entry.getValue()));
        return candidates;
    }

    /**
     * Final page rerank only needs the strongest sibling pages inside each candidate book. Limiting this window keeps
     * the expensive cross-encoder focused on the few local competitors that can plausibly beat each other.
     */
    private Map<String, List<TextbookSearchHit>> cappedSupportHitsByDocId(
            Map<String, List<TextbookSearchHit>> hitsByDocId,
            Map<String, List<TextbookSearchHit>>... candidateRoutes) {
        Map<String, List<TextbookSearchHit>> capped = new LinkedHashMap<>();
        for (Map.Entry<String, List<TextbookSearchHit>> entry : hitsByDocId.entrySet()) {
            int limit = retrievalProperties.rerank().maxPagesPerDocument();
            LinkedHashMap<String, TextbookSearchHit> selected = new LinkedHashMap<>();
            for (Map<String, List<TextbookSearchHit>> route : candidateRoutes) {
                List<TextbookSearchHit> candidates = route == null
                        ? List.of()
                        : route.getOrDefault(entry.getKey(), List.of());
                appendSupportCandidates(selected, candidates.stream().limit(1).toList(), limit);
            }
            for (Map<String, List<TextbookSearchHit>> route : candidateRoutes) {
                List<TextbookSearchHit> candidates = route == null
                        ? List.of()
                        : route.getOrDefault(entry.getKey(), List.of());
                appendSupportCandidates(selected, candidates, limit);
            }
            appendSupportCandidates(selected, entry.getValue(), limit);
            capped.put(entry.getKey(), List.copyOf(selected.values()));
        }
        return capped;
    }

    /**
     * Retain route diversity before the per-document cap. One extracted
     * section may contain an OCR heading, figure caption, formula, and prose
     * blocks; those siblings are one semantic evidence unit and must not use
     * every support slot merely because their chunk ids differ.
     */
    private static void appendSupportCandidates(
            Map<String, TextbookSearchHit> selected,
            List<TextbookSearchHit> candidates,
            int limit) {
        if (candidates == null || candidates.isEmpty() || selected.size() >= limit) {
            return;
        }
        for (TextbookSearchHit candidate : candidates) {
            if (candidate == null || supportEvidenceKey(candidate).isBlank()) {
                continue;
            }
            selected.putIfAbsent(supportEvidenceKey(candidate), candidate);
            if (selected.size() >= limit) {
                return;
            }
        }
    }

    /**
     * Deduplicates sibling blocks from one visible page while retaining every page of a cross-page section.
     *
     * <p>A stable section id now spans a small heading's continuation pages.  Using only that id here would suppress
     * the later pages before rerank, leaving the cross-encoder unable to select the actual page requested by a user.
     * The page number keeps heading/prose/caption siblings from consuming the window together but preserves genuine
     * cross-page evidence.</p>
     */
    static String supportEvidenceKey(TextbookSearchHit candidate) {
        if (candidate == null) {
            return "";
        }
        String identity = firstNonBlank(candidate.sectionId(), candidate.chunkId());
        if (identity.isBlank()) {
            return "";
        }
        return firstNonBlank(candidate.docId(), "unknown-document") + "#" + identity + "#" + candidate.pageNo();
    }

    /**
     * Interleaves the independently ordered lexical and semantic document routes without mixing their scores.
     * Duplicate ids consume one slot, so a document supported by both routes does not crowd out independent evidence.
     */
    static List<String> interleaveDocumentIds(
            List<String> lexicalDocIds,
            List<String> semanticDocIds,
            int limit) {
        return interleaveDocumentIds(List.of(
                lexicalDocIds == null ? List.of() : lexicalDocIds,
                semanticDocIds == null ? List.of() : semanticDocIds), limit);
    }

    /**
     * Interleaves independently ranked routes under the existing global document cap.
     *
     * <p>The title route is admitted in round-robin order with body BM25 and
     * BGE. No route score is combined with another route's score.</p>
     */
    static List<String> interleaveDocumentIds(List<List<String>> routes, int limit) {
        int safeLimit = Math.max(0, limit);
        if (safeLimit == 0) {
            return List.of();
        }
        List<List<String>> normalizedRoutes = routes == null ? List.of() : routes.stream()
                .map(route -> route == null ? List.<String>of() : route)
                .toList();
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        int offset = 0;
        while (selected.size() < safeLimit && hasRouteValueAt(normalizedRoutes, offset)) {
            for (List<String> route : normalizedRoutes) {
                appendDocumentId(selected, route, offset, safeLimit);
                if (selected.size() >= safeLimit) {
                    break;
                }
            }
            offset += 1;
        }
        return List.copyOf(selected);
    }

    /** Avoids score-dependent admission by checking only whether one route has another ranked document. */
    private static boolean hasRouteValueAt(List<List<String>> routes, int offset) {
        if (routes == null || offset < 0) {
            return false;
        }
        for (List<String> route : routes) {
            if (route != null && offset < route.size()) {
                return true;
            }
        }
        return false;
    }

    /** Adds one valid route-local document while preserving the configured global window. */
    private static void appendDocumentId(
            LinkedHashSet<String> selected,
            List<String> source,
            int offset,
            int limit) {
        if (selected.size() >= limit || offset < 0 || offset >= source.size()) {
            return;
        }
        String docId = source.get(offset);
        if (docId != null && !docId.isBlank()) {
            selected.add(docId);
        }
    }


    /**
     * Page candidates are bounded before the cross-encoder stage.
     *
     * <p>Do not let a large textbook chapter send every lexically-positive sibling page into the reranker. The page
     * reranker must judge close competitors, not re-scan the entire chapter. The request limit still controls the
     * final response size, but this candidate window controls worker latency.</p>
     */
    private List<TextbookSearchHit> pageCandidates(
            List<String> rankedDocIds,
            Map<String, List<TextbookSearchHit>> supportHitsByDocId,
            int safeLimit) {
        List<TextbookSearchHit> candidates = new ArrayList<>();
        int candidateLimit = retrievalProperties.rerank().pageCandidateLimit();
        /*
         * Do not let the first admitted edition consume the entire page rerank window. Different textbook editions
         * frequently contain the same concept on nearby but non-identical pages; round-robin admission gives every
         * stage-one document evidence a chance to expose its strongest sibling page to the one real rerank call.
         */
        int pageOffset = 0;
        while (candidates.size() < candidateLimit) {
            boolean appended = false;
            for (String docId : rankedDocIds) {
                List<TextbookSearchHit> documentHits = supportHitsByDocId.getOrDefault(docId, List.of());
                if (pageOffset < documentHits.size()) {
                    candidates.add(documentHits.get(pageOffset));
                    appended = true;
                    if (candidates.size() >= candidateLimit) {
                        break;
                    }
                }
            }
            if (!appended) {
                break;
            }
            pageOffset += 1;
        }
        return List.copyOf(candidates);
    }

    /**
     * Uses the configured rerank endpoint when available and falls back inside VectorIndexService to embedding cosine
     * similarity. Keep the fallback centralized there so textbook retrieval does not grow its own heuristic score path.
     */
    private Map<String, Double> semanticScoreByKey(
            String query,
            Map<String, String> candidateTexts,
            List<TextbookRetrievalStage> executionStages) {
        if (candidateTexts.isEmpty()) {
            return Map.of();
        }
        long startedAt = System.nanoTime();
        List<String> keys = new ArrayList<>(candidateTexts.keySet());
        List<String> texts = keys.stream().map(candidateTexts::get).toList();
        VectorTextRerankResult rerank = vectorIndexService.rerankTextsWithTrace(query, texts);
        List<Double> scores = rerank.scores();
        boolean crossEncoder = VectorTextRerankResult.CROSS_ENCODER.equals(rerank.strategy());
        executionStages.add(new TextbookRetrievalStage(
                "bge_rerank",
                crossEncoder ? "BGE 证据重排" : "嵌入相似度回退",
                crossEncoder ? "completed" : "fallback",
                crossEncoder
                        ? "已对 " + texts.size() + " 个候选页执行真实 cross-encoder 重排。"
                        : "cross-encoder 不可用，已明确使用 embedding cosine 对 " + texts.size() + " 个候选页排序。",
                elapsedMs(startedAt)));
        Map<String, Double> scoreByKey = new LinkedHashMap<>();
        for (int index = 0; index < keys.size() && index < scores.size(); index += 1) {
            scoreByKey.put(keys.get(index), scores.get(index));
        }
        return Map.copyOf(scoreByKey);
    }

    private Map<String, String> pageCandidateTexts(
            String query,
            List<TextbookSearchHit> hits,
            LogicalBlockIndex blockIndex) {
        Map<String, String> texts = new LinkedHashMap<>();
        for (TextbookSearchHit hit : hits) {
            texts.put(hit.chunkId(), semanticLogicalBlockText(query, hit, blockIndex));
        }
        return texts;
    }

    /**
     * Builds a rerank payload only after the bounded final candidate window is known.
     *
     * <p>The corpus snapshot keeps one copy of every c2 chunk.  This method is the only place sibling text is joined,
     * so at most the configured rerank window allocates a transient block string for one request.</p>
     */
    private String semanticLogicalBlockText(
            String query,
            TextbookSearchHit hit,
            LogicalBlockIndex blockIndex) {
        String blockKey = blockKeyForHit(hit, blockIndex);
        List<TextbookChunk> members = blockKey == null || blockIndex == null
                ? List.of()
                : blockIndex.membersByBlockKey().getOrDefault(blockKey, List.of());
        if (members.isEmpty()) {
            return semanticPageText(query, hit);
        }
        StringBuilder body = new StringBuilder();
        StringBuilder formulas = new StringBuilder();
        for (TextbookChunk member : members) {
            appendLine(body, member.text());
            appendLine(formulas, member.formulaText());
        }
        StringBuilder builder = new StringBuilder();
        appendLine(builder, hit.bookName());
        appendLine(builder, hit.volume());
        appendLine(builder, String.join(" / ", hit.chapterPath() == null ? List.of() : hit.chapterPath()));
        appendLine(builder, hit.sectionTitle());
        appendLine(builder, hit.printedPageNo());
        appendLine(builder, evidenceWindow(query, body.toString(), retrievalProperties.rerank().pageTextChars()));
        appendLine(builder, truncateForRerank(formulas.toString(), retrievalProperties.rerank().formulaTextChars()));
        return builder.toString();
    }

    /**
     * Document-level rerank asks a simple question: "does this textbook contain the right evidence somewhere in these
     * candidate pages?" We therefore concatenate only the already-recalled pages from that book instead of inventing a
     * new document summary or relying on fragile filename features.
     *
     * <p>Do not send whole OCR pages into the reranker. Real textbook pages can contain large OCR noise and long
     * formula dumps; once several candidate pages are concatenated, the worker times out before producing a score.
     * Keep a bounded semantic digest here so rerank latency stays predictable while still exposing chapter/section and
     * the most relevant snippet text.</p>
     */
    private String semanticPageText(String query, TextbookSearchHit hit) {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, hit.bookName());
        appendLine(builder, hit.volume());
        appendLine(builder, String.join(" / ", hit.chapterPath() == null ? List.of() : hit.chapterPath()));
        appendLine(builder, hit.sectionTitle());
        appendLine(builder, hit.printedPageNo());
        appendLine(builder, evidenceWindow(query, hit.textSnippet(), retrievalProperties.rerank().pageTextChars()));
        appendLine(builder, truncateForRerank(hit.formulaText(), retrievalProperties.rerank().formulaTextChars()));
        return builder.toString();
    }

    /**
     * Selects the most query-grounded window inside a page before passing it to the cross-encoder.
     *
     * <p>OCR pages usually open with headers, page numbers, or a preceding example while the exact exercise appears
     * later. The selector never assigns a retrieval rank and never substitutes semantic retrieval: BGE already chose
     * the page. It only ensures the bounded rerank payload preserves the strongest available evidence instead of
     * always truncating from character zero.</p>
     */
    private static String evidenceWindow(String query, String content, int maxChars) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").strip();
        if (normalized.isBlank() || maxChars <= 0 || normalized.length() <= maxChars) {
            return normalized;
        }
        List<String> queryUnits = queryEvidenceUnits(query);
        if (queryUnits.isEmpty()) {
            return truncateForRerank(normalized, maxChars);
        }
        int bestStart = 0;
        int bestScore = Integer.MIN_VALUE;
        for (int start = 0; start < normalized.length(); start += maxChars) {
            int end = Math.min(normalized.length(), start + maxChars);
            String window = normalized.substring(start, end);
            int score = 0;
            for (String unit : queryUnits) {
                if (window.contains(unit)) {
                    score += unit.length();
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestStart = start;
            }
        }
        int bestEnd = Math.min(normalized.length(), bestStart + maxChars);
        return normalized.substring(bestStart, bestEnd).strip();
    }

    /** Produces content-bearing CJK/numeric spans, rather than a topic-specific keyword dictionary. */
    private static List<String> queryEvidenceUnits(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> units = new LinkedHashSet<>();
        for (String token : query.replaceAll("[^\\p{IsHan}A-Za-z0-9.]+", " ").split("\\s+")) {
            String normalized = token.strip();
            if (normalized.length() >= 2) {
                units.add(normalized);
            }
        }
        String compact = query.replaceAll("\\s+", "");
        for (int index = 0; index + 1 < compact.length(); index += 1) {
            String bigram = compact.substring(index, index + 2);
            if (bigram.codePoints().allMatch(Character::isLetterOrDigit)) {
                units.add(bigram);
            }
        }
        return List.copyOf(units);
    }

    /**
     * Rerank quality depends on sending the strongest semantic clue, not the entire raw OCR payload. Truncate only for
     * model I/O control; caller-visible evidence and snippets remain untouched in the final response.
     */
    private static String truncateForRerank(String value, int maxChars) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || maxChars <= 0 || normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars).strip() + "…";
    }

    private static void appendLine(StringBuilder builder, String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(normalized);
    }

    private static TextbookSearchHit withScore(TextbookSearchHit hit, double score) {
        return new TextbookSearchHit(
                hit.chunkId(),
                hit.sectionId(),
                score,
                hit.retrievalStrategy(),
                hit.docId(),
                hit.bookName(),
                hit.volume(),
                hit.chapterPath(),
                hit.pageNo(),
                hit.printedPageNo(),
                hit.sectionTitle(),
                hit.textSnippet(),
                hit.formulaText(),
                hit.sourcePageImage(),
                hit.pageQualityLabel(),
                hit.pageImageUri());
    }

    /**
     * 教材原始 chunk 只保存相对图片路径；真正返回给前端和 AI 的必须是后端受控 URI，避免调用方拼接本地路径。
     */
    private List<TextbookSearchHit> attachControlledPageImageUris(List<TextbookSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        return hits.stream()
                .map(this::attachControlledPageImageUri)
                .toList();
    }

    /**
     * 只在页码有效且当前命中还没携带 URI 时补地址，这样既兼容旧缓存，也避免覆盖上游已经显式指定的值。
     */
    private TextbookSearchHit attachControlledPageImageUri(TextbookSearchHit hit) {
        if (hit == null) {
            return null;
        }
        if (hit.pageImageUri() != null && !hit.pageImageUri().isBlank()) {
            return hit;
        }
        if (hit.pageNo() <= 0 || hit.docId() == null || hit.docId().isBlank()) {
            return hit;
        }
        return hit.withPageImageUri(pageImageService.pageImageUri(hit.docId(), hit.pageNo()));
    }

    /**
     * Creates only a reference index over the already-loaded c2 small-heading records.
     *
     * <p>Each list stores references to the original {@link TextbookChunk}, never a copied text field. A visible
     * heading remains part of the key because old section identities can be shared by different headings. Cross-page
     * member text is materialized only by {@link #semanticLogicalBlockText(String, TextbookSearchHit, LogicalBlockIndex)}
     * after final candidate admission.</p>
     */
    static LogicalBlockIndex logicalSectionIndex(List<TextbookChunk> sourceChunks) {
        if (sourceChunks == null || sourceChunks.isEmpty()) {
            return LogicalBlockIndex.EMPTY;
        }
        Map<String, List<TextbookChunk>> mutableMembers = new LinkedHashMap<>();
        Map<String, TextbookChunk> originalByChunkId = new LinkedHashMap<>();
        Map<String, String> blockKeyByOriginalChunkId = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> mutableSectionKeys = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> mutablePageKeys = new LinkedHashMap<>();
        for (TextbookChunk chunk : sourceChunks) {
            if (chunk == null || chunk.docId() == null || chunk.docId().isBlank()) {
                continue;
            }
            String sectionIdentity = firstNonBlank(chunk.sectionId(), chunk.chunkId());
            String blockKey = chunk.docId() + "#" + sectionIdentity + "#" + visibleSectionTitle(chunk.sectionTitle());
            mutableMembers.computeIfAbsent(blockKey, ignored -> new ArrayList<>()).add(chunk);
            if (chunk.chunkId() != null && !chunk.chunkId().isBlank()) {
                originalByChunkId.put(chunk.chunkId(), chunk);
                blockKeyByOriginalChunkId.put(chunk.chunkId(), blockKey);
            }
            mutableSectionKeys.computeIfAbsent(chunk.docId() + "#" + sectionIdentity, ignored -> new LinkedHashSet<>())
                    .add(blockKey);
            mutablePageKeys.computeIfAbsent(chunk.docId() + "#" + chunk.pageNo(), ignored -> new LinkedHashSet<>())
                    .add(blockKey);
        }
        Map<String, List<TextbookChunk>> membersByBlockKey = new LinkedHashMap<>();
        Map<String, TextbookChunk> representatives = new LinkedHashMap<>();
        for (Map.Entry<String, List<TextbookChunk>> entry : mutableMembers.entrySet()) {
            List<TextbookChunk> ordered = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(TextbookChunk::pageNo)
                            .thenComparing(chunk -> textOrBlank(chunk.chunkId())))
                    .toList();
            membersByBlockKey.put(entry.getKey(), List.copyOf(ordered));
            representatives.put(entry.getKey(), ordered.stream()
                    .max(Comparator.comparingInt(TextbookRetrievalService::evidenceLength)
                            .thenComparing(chunk -> textOrBlank(chunk.chunkId())))
                    .orElse(ordered.getFirst()));
        }
        return new LogicalBlockIndex(
                Map.copyOf(membersByBlockKey),
                Map.copyOf(originalByChunkId),
                Map.copyOf(blockKeyByOriginalChunkId),
                immutableBlockKeyLists(mutableSectionKeys),
                immutableBlockKeyLists(mutablePageKeys),
                Map.copyOf(representatives));
    }

    /** Freezes nested reference lists so one request cannot mutate a cached corpus index. */
    private static Map<String, List<String>> immutableBlockKeyLists(
            Map<String, LinkedHashSet<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((key, values) -> result.put(key, List.copyOf(values)));
        return Map.copyOf(result);
    }

    /**
     * 加载教材语料缓存；当多个请求同时缓存未命中时，只允许一个线程执行实际加载。
     */
    private CachedTextbookCorpus loadCorpus(Path processedBooksRoot) {
        try {
            return loadCorpusOrThrow(processedBooksRoot);
        } catch (RuntimeException e) {
            return recoverFromLoadFailure(processedBooksRoot, e);
        }
    }

    /**
     * 执行真实语料加载流程：先校验失败冷却，再计算源文件签名，最后在锁内单飞加载。
     */
    private CachedTextbookCorpus loadCorpusOrThrow(Path processedBooksRoot) {
        rejectDuringFailureCooldown(processedBooksRoot);
        List<TextbookCatalogItem> books = catalogReader.read(processedBooksRoot.resolve("catalog.jsonl"));
        List<Path> chunkPaths = books.stream()
                .map(book -> chunksPath(processedBooksRoot, book))
                .toList();
        List<SourceFileSignature> signatures = sourceSignatures(
                processedBooksRoot.resolve("catalog.jsonl"),
                chunkPaths,
                retrievalIndexManifestPaths(processedBooksRoot));
        CachedTextbookCorpus snapshot = cachedCorpus;
        if (isCurrentSnapshot(snapshot, processedBooksRoot, signatures)) {
            return snapshot;
        }

        synchronized (corpusLoadLock) {
            rejectDuringFailureCooldown(processedBooksRoot);
            snapshot = cachedCorpus;
            if (isCurrentSnapshot(snapshot, processedBooksRoot, signatures)) {
                return snapshot;
            }
            CachedTextbookCorpus loaded = readCorpus(processedBooksRoot, signatures, chunkPaths);
            cachedCorpus = loaded;
            lastLoadFailure = null;
            return loaded;
        }
    }

    /**
     * 判断缓存快照是否仍对应当前根目录和源文件签名。
     */
    private static boolean isCurrentSnapshot(
            CachedTextbookCorpus snapshot,
            Path processedBooksRoot,
            List<SourceFileSignature> signatures) {
        return snapshot != null
                && snapshot.processedBooksRoot().equals(processedBooksRoot)
                && snapshot.signatures().equals(signatures);
    }

    /**
     * 加载失败后的恢复策略：同根目录旧缓存优先返回；没有旧缓存时记录失败并抛出可观测异常。
     */
    private CachedTextbookCorpus recoverFromLoadFailure(Path processedBooksRoot, RuntimeException failure) {
        CachedTextbookCorpus snapshot = cachedCorpus;
        lastLoadFailure = new CorpusLoadFailure(processedBooksRoot, System.nanoTime(), failure.getMessage());
        if (snapshot != null && snapshot.processedBooksRoot().equals(processedBooksRoot)) {
            return snapshot;
        }
        throw new IllegalStateException("Failed to load textbook corpus: " + failure.getMessage(), failure);
    }

    /**
     * 冷却期内阻断重复加载：无旧缓存时快速失败，有旧缓存时由上层恢复逻辑返回旧快照。
     */
    private void rejectDuringFailureCooldown(Path processedBooksRoot) {
        CorpusLoadFailure failure = lastLoadFailure;
        if (failure == null || !failure.processedBooksRoot().equals(processedBooksRoot)) {
            return;
        }
        long elapsedNanos = System.nanoTime() - failure.failedAtNanos();
        if (elapsedNanos < LOAD_FAILURE_COOLDOWN_NANOS) {
            throw new IllegalStateException("Textbook corpus load cooldown active after failure: " + failure.reason());
        }
    }

    /**
     * 从 chunk 文件读取完整教材语料，并封装为不可变缓存快照。
     */
    private CachedTextbookCorpus readCorpus(
            Path processedBooksRoot,
            List<SourceFileSignature> signatures,
            List<Path> chunkPaths) {
        List<TextbookChunk> chunks = new ArrayList<>();
        for (Path chunksPath : chunkPaths) {
            chunks.addAll(chunkReader.read(chunksPath));
        }
            CachedTextbookCorpus loaded = new CachedTextbookCorpus(
                    processedBooksRoot,
                    signatures,
                    corpusSignatureHash(processedBooksRoot, signatures),
                    List.copyOf(chunks),
                    logicalSectionIndex(chunks));
            return loaded;
    }

    /**
     * 计算 catalog 和 chunk 文件签名，用于判断缓存是否仍然有效。
     */
    private static List<SourceFileSignature> sourceSignatures(
            Path catalogPath,
            List<Path> chunkPaths,
            List<Path> retrievalIndexManifestPaths) {
        List<SourceFileSignature> signatures = new ArrayList<>();
        signatures.add(sourceSignature(catalogPath));
        chunkPaths.stream().map(TextbookRetrievalService::sourceSignature).forEach(signatures::add);
        // The worker's page-text/CLIP manifests are part of stage-one evidence.
        // Including their real file signatures makes a rebuilt index invalidate
        // only textbook-result cache keys, without flushing unrelated Redis data.
        retrievalIndexManifestPaths.stream().map(TextbookRetrievalService::sourceSignature).forEach(signatures::add);
        return List.copyOf(signatures);
    }

    /** Returns only present worker manifests so lexical retrieval still works while an optional index is rebuilt. */
    private static List<Path> retrievalIndexManifestPaths(Path processedBooksRoot) {
        return List.of(
                        processedBooksRoot.resolve("_page_text_index/manifest.json"),
                        processedBooksRoot.resolve("_page_image_index/manifest.json"))
                .stream()
                .filter(Files::isRegularFile)
                .toList();
    }

    /**
     * 读取单个源文件的大小和最后修改时间作为缓存签名。
     */
    private static SourceFileSignature sourceSignature(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            return new SourceFileSignature(
                    normalized,
                    Files.size(normalized),
                    Files.getLastModifiedTime(normalized).to(TimeUnit.NANOSECONDS));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to fingerprint textbook source file: " + path, e);
        }
    }

    /**
     * 根据 catalog 中的 book_root 找到优先使用的 AI chunk 文件。
     */
    private static Path chunksPath(Path processedBooksRoot, TextbookCatalogItem book) {
        Path bookRoot = Paths.get(book.bookRoot());
        if (!bookRoot.isAbsolute()) {
            bookRoot = processedBooksRoot.resolve(bookRoot);
        }
        Path aiChunks = bookRoot.resolve("jsonl_ai/chunks.jsonl");
        if (Files.exists(aiChunks)) {
            return aiChunks;
        }
        Path textChunks = bookRoot.resolve("jsonl/chunks.jsonl");
        if (Files.exists(textChunks)) {
            return textChunks;
        }
        throw new IllegalStateException("Missing textbook chunks for book " + book.docId() + ": " + bookRoot);
    }

    /**
     * 把纳秒耗时转换为毫秒，并避免整数溢出。
     */
    private static int elapsedMs(long startedAtNanos) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        return elapsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsed;
    }

    /**
     * Loads and signatures the textbook corpus before the first user search.
     *
     * <p>This deliberately does not call an embedding or rerank model. It moves filesystem parsing out of the first
     * request while preserving the existing signature check, so edits to processed_books still invalidate the snapshot
     * on the next retrieval.</p>
     */
    public void warmCorpus(Path processedBooksRoot) {
        if (processedBooksRoot != null) {
            CachedTextbookCorpus corpus = loadCorpus(processedBooksRoot.toAbsolutePath().normalize());
            searchEngine.prepareCorpus(corpus.chunks());
        }
    }

    /** Builds the response explanation from execution facts rather than from the internal pipeline version string. */
    private static String retrievalDescription(
            TextbookRetrievalMode mode,
            List<TextbookChunk> scopedChunks,
            List<TextbookSearchHit> hits,
            boolean cacheHit) {
        if (scopedChunks == null || scopedChunks.isEmpty()) {
            return "所选教材库中没有可检索页面，请调整教材范围后重试。";
        }
        if (hits == null || hits.isEmpty()) {
            return "已执行" + mode.label() + "，但召回链路没有返回可排序的教材候选页。";
        }
        return "已执行" + mode.label() + "并返回 " + hits.size() + " 条教材证据；实际重排方式见执行阶段。"
                + (cacheHit ? " 本次复用了同一条件下的教材检索结果缓存。" : "");
    }

    /** Creates explicit, localized stage state for the UI audit panel. */
    private static List<TextbookRetrievalStage> retrievalStages(
            TextbookRetrievalMode mode,
            List<TextbookChunk> scopedChunks,
            List<TextbookSearchHit> hits,
            boolean cacheHit,
            List<TextbookRetrievalStage> executionStages) {
        if (scopedChunks == null || scopedChunks.isEmpty()) {
            return List.of(new TextbookRetrievalStage("scope", "教材范围", "empty", "所选教材库未包含任何可检索页面。"));
        }
        List<TextbookRetrievalStage> stages = new ArrayList<>();
        stages.add(new TextbookRetrievalStage("scope", "教材范围", "completed", "已在选定教材范围内检索。"));
        if (cacheHit) {
            stages.add(new TextbookRetrievalStage("cache", "教材检索结果缓存", "hit", "复用相同教材范围与策略的检索结果。"));
            return List.copyOf(stages);
        }
        if (executionStages != null && !executionStages.isEmpty()) {
            stages.addAll(executionStages);
        }
        if (mode.usesTextPageIndex()) {
            appendMissingStage(stages, "bge_page", "BGE 文本页召回", "skipped", "当前请求没有可提交给 BGE 的文本候选。");
        }
        if (mode.usesClipPageIndex()) {
            appendMissingStage(stages, "clip_page", "CLIP 页面图像召回", "skipped", "当前请求没有可提交给 CLIP 的图片或文本候选。");
        }
        appendMissingStage(
                stages,
                "bge_rerank",
                "证据重排",
                "skipped",
                hits == null || hits.isEmpty() ? "没有候选页可执行重排。" : "本次请求未记录重排执行方式，不声明重排已完成。");
        return List.copyOf(stages);
    }

    /** Adds a stage only when the request did not already record its actual worker execution. */
    private static void appendMissingStage(
            List<TextbookRetrievalStage> stages, String code, String label, String status, String description) {
        boolean present = stages.stream().anyMatch(stage -> code.equals(stage.code()));
        if (!present) {
            stages.add(new TextbookRetrievalStage(code, label, status, description));
        }
    }

    private static List<TextbookChunk> scopedChunks(List<TextbookChunk> chunks, List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return chunks;
        }
        java.util.Set<String> allowed = java.util.Set.copyOf(documentIds);
        return chunks.stream().filter(chunk -> allowed.contains(chunk.docId())).toList();
    }

    private String searchCacheKey(
            CachedTextbookCorpus corpus,
            int limit,
            String focusedQuery,
            List<String> documentIds,
            TextbookRetrievalMode mode) {
        return sha256(String.join("|",
                SEARCH_CACHE_SCHEMA_VERSION,
                // Candidate admission is part of response semantics. Keep the
                // executable pipeline version in the cache key so a new route
                // cannot inherit an older route's ranked evidence.
                SEARCH_PIPELINE_VERSION,
                corpus.processedBooksRoot().toString(),
                corpus.signatureHash(),
                retrievalProperties.rerank().cacheIdentity(),
                String.valueOf(limit),
                textOrBlank(focusedQuery),
                mode == null ? TextbookRetrievalMode.HYBRID.code() : mode.code(),
                String.join(",", documentIds == null ? List.of() : documentIds)));
    }

    private static String textOrBlank(String value) {
        return value == null ? "" : value;
    }

    private static String corpusSignatureHash(
            Path processedBooksRoot,
            List<SourceFileSignature> signatures) {
        StringBuilder payload = new StringBuilder(processedBooksRoot.toString());
        for (SourceFileSignature signature : signatures) {
            payload.append('|')
                    .append(signature.path())
                    .append(':')
                    .append(signature.size())
                    .append(':')
                    .append(signature.lastModifiedNanos());
        }
        return sha256(payload.toString());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private record SourceFileSignature(Path path, long size, long lastModifiedNanos) {
    }

    /**
     * 教材语料加载失败状态。
     *
     * @param processedBooksRoot 失败对应的教材处理产物根目录。
     * @param failedAtNanos 使用 System.nanoTime 记录的失败时间，用于计算冷却窗口。
     * @param reason 最近一次失败原因，便于日志、测试和接口错误排查。
     */
    private record CorpusLoadFailure(Path processedBooksRoot, long failedAtNanos, String reason) {
    }

    private record CachedTextbookCorpus(
            Path processedBooksRoot,
            List<SourceFileSignature> signatures,
            String signatureHash,
            List<TextbookChunk> chunks,
            LogicalBlockIndex logicalBlockIndex) {
    }

    /** Immutable, lightweight lookup over original small-heading records. */
    static record LogicalBlockIndex(
            Map<String, List<TextbookChunk>> membersByBlockKey,
            Map<String, TextbookChunk> originalByChunkId,
            Map<String, String> blockKeyByOriginalChunkId,
            Map<String, List<String>> blockKeysByDocumentSection,
            Map<String, List<String>> blockKeysByDocumentPage,
            Map<String, TextbookChunk> representativeByBlockKey) {
        private static final LogicalBlockIndex EMPTY = new LogicalBlockIndex(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    private record TextbookPageCandidate(
            TextbookSearchHit hit,
            double pageSemanticScore) {
    }

}
