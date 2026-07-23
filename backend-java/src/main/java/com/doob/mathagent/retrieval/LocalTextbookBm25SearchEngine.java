package com.doob.mathagent.retrieval;

import com.doob.mathagent.resources.TextbookChunk;
import com.doob.mathagent.teacher.search.TeacherResourceGraphAlignmentService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LocalTextbookBm25SearchEngine {

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final Pattern ASCII_TERM = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern QUERY_CLAUSE_SPLITTER = Pattern.compile("[\\r\\n,，。；;：:！？!?()（）\\[\\]【】]+");
    private static final List<String> EMPTY_PAGE_MARKERS = List.of(
            "本页文本层为空",
            "需 ocr",
            "需要 ocr",
            "视觉模型补充",
            "text layer is empty",
            "ocr required");
    /**
     * Snippet budgets only control what evidence text is sent downstream to the semantic reranker and callers.
     *
     * <p>They are not ranking weights. The lexical stage still admits pages by BM25, but the snippet must preserve the
     * query-relevant sentence instead of blindly truncating the page head; otherwise the real reranker never sees the
     * distinguishing evidence for long textbook pages.</p>
     */
    private static final int MAX_SNIPPET_CHARS = 360;
    private static final int MAX_SNIPPET_WINDOWS = 3;
    private static final int SNIPPET_WINDOW_RADIUS = 88;

    private final TextbookPageQualityClassifier pageQualityClassifier;
    /**
     * Prepared statistics are keyed by the immutable chunk-list identity from TextbookRetrievalService's corpus
     * snapshot. A source update produces a new snapshot/list and automatically builds a new index without making
     * every query re-tokenize the entire textbook corpus.
     */
    private final Map<List<TextbookChunk>, PreparedCorpus> preparedCorpora =
            Collections.synchronizedMap(new IdentityHashMap<>());

    public LocalTextbookBm25SearchEngine() {
        this(new TextbookPageQualityClassifier());
    }

    LocalTextbookBm25SearchEngine(TextbookPageQualityClassifier pageQualityClassifier) {
        this.pageQualityClassifier = pageQualityClassifier;
    }

    public List<TextbookSearchHit> search(String query, List<TextbookChunk> chunks, int limit) {
        return search(query, chunks, limit, TeacherResourceGraphAlignmentService.QueryGraphContext.EMPTY);
    }

    /**
     * Searches visible small-heading text as an independent lexical evidence route.
     *
     * <p>This is fielded retrieval, not a title-score boost: body BM25 and title
     * BM25 keep separate rankings and {@link TextbookRetrievalService} admits
     * them by route interleaving.  The prepared corpus retains only title term
     * statistics and references to the original chunks, so no second body-text
     * corpus is materialized in memory.</p>
     */
    public List<TextbookSearchHit> searchSectionTitles(String query, List<TextbookChunk> chunks, int limit) {
        List<String> queryTerms = terms(query);
        if (queryTerms.isEmpty() || chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        PreparedCorpus corpus = preparedCorpus(chunks);
        Map<String, Long> queryCounts = frequency(queryTerms);
        List<String> snippetTerms = snippetTerms(query, TeacherResourceGraphAlignmentService.QueryGraphContext.EMPTY);
        String compactQuery = compact(query);
        List<PageSignal> positiveSignals = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index += 1) {
            TextbookChunk chunk = chunks.get(index);
            double lexicalScore = bm25Score(
                    queryCounts,
                    corpus.titleTermFrequencies().get(index),
                    corpus.titleDocumentFrequencies(),
                    corpus.titleDocumentLengths().get(index),
                    corpus.titleAverageLength(),
                    chunks.size());
            if (lexicalScore <= 0.0d) {
                continue;
            }
            String compactTitle = corpus.compactSectionTitles().get(index);
            String qualityLabel = pageQualityClassifier.label(chunk, corpus.maxPageByDocId().getOrDefault(chunk.docId(), 0));
            positiveSignals.add(new PageSignal(
                    chunk,
                    lexicalScore * pageQualityClassifier.scoreFactor(qualityLabel),
                    !compactQuery.isBlank() && compactTitle.contains(compactQuery),
                    lexicalMatchCount(compactTitle, queryTerms),
                    0,
                    qualityLabel));
        }
        return rankedHits(positiveSignals, limit, snippetTerms, "local_title_bm25");
    }

    /**
     * Prepares immutable corpus statistics without issuing a query or invoking an AI model.
     *
     * <p>TextbookRetrievalWarmup calls this after loading processed_books so the first teacher search only executes
     * query-side scoring and semantic rerank.</p>
     */
    public void prepareCorpus(List<TextbookChunk> chunks) {
        if (chunks != null && !chunks.isEmpty()) {
            preparedCorpus(chunks);
        }
    }

    /**
     * Runs only the lexical coarse-recall stage for textbooks.
     *
     * <p>Do not let this local engine become a second final-ranking system. Its job is only to surface plausible
     * textbook pages/books for the semantic rerank stage in {@link TextbookRetrievalService}. BM25 stays primary here;
     * graph aliases and section metadata break ties between already-positive lexical hits. Page-quality factors are
     * applied to the lexical score itself so a directory/cover hit cannot outrank the textbook body merely because
     * the directory repeats an exact section title.</p>
     */
    public List<TextbookSearchHit> search(
            String query,
            List<TextbookChunk> chunks,
            int limit,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
        List<String> queryTerms = terms(query);
        if (queryTerms.isEmpty() || chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        PreparedCorpus corpus = preparedCorpus(chunks);
        Map<String, Long> queryCounts = frequency(queryTerms);
        Set<String> graphTerms = queryGraphTerms(queryGraph, queryTerms);
        List<String> snippetTerms = snippetTerms(query, queryGraph);
        String compactQuery = compact(query);

        List<PageSignal> positiveSignals = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index += 1) {
            TextbookChunk chunk = chunks.get(index);
            double lexicalScore = bm25Score(
                    queryCounts,
                    corpus.termFrequencies().get(index),
                    corpus.documentFrequencies(),
                    corpus.documentLengths().get(index),
                    corpus.averageLength(),
                    chunks.size());
            if (lexicalScore <= 0.0d) {
                continue;
            }
            String compactMetadata = corpus.compactMetadataTexts().get(index);
            String compactSurface = corpus.compactSurfaceTexts().get(index);
            String qualityLabel = pageQualityClassifier.label(chunk, corpus.maxPageByDocId().getOrDefault(chunk.docId(), 0));
            double qualityAdjustedScore = lexicalScore * pageQualityClassifier.scoreFactor(qualityLabel);
            positiveSignals.add(new PageSignal(
                    chunk,
                    qualityAdjustedScore,
                    !compactQuery.isBlank() && compactSurface.contains(compactQuery),
                    lexicalMatchCount(compactMetadata, queryTerms),
                    lexicalMatchCount(compactMetadata, graphTerms),
                    qualityLabel));
        }

        return rankedHits(positiveSignals, limit, snippetTerms, "local_bm25");
    }

    /** Applies the common document/page ordering while preserving route-specific BM25 scores. */
    private List<TextbookSearchHit> rankedHits(
            List<PageSignal> positiveSignals,
            int limit,
            List<String> snippetTerms,
            String retrievalStrategy) {
        if (positiveSignals.isEmpty()) {
            return List.of();
        }
        int effectiveLimit = limit > 0 ? limit : 10;
        Map<String, List<PageSignal>> pagesByDoc = new LinkedHashMap<>();
        for (PageSignal signal : positiveSignals) {
            pagesByDoc.computeIfAbsent(signal.chunk().docId(), ignored -> new ArrayList<>()).add(signal);
        }
        List<DocumentSignal> rankedDocuments = pagesByDoc.entrySet().stream()
                .map(entry -> toDocumentSignal(entry.getKey(), entry.getValue()))
                .sorted(documentSignalComparator())
                .toList();

        List<TextbookSearchHit> hits = new ArrayList<>();
        for (DocumentSignal document : rankedDocuments) {
            document.pages().stream()
                    .sorted(pageSignalComparator())
                    .map(page -> toHit(
                            page.chunk(),
                            roundScore(page.lexicalScore()),
                            page.qualityLabel(),
                            snippetTerms,
                            retrievalStrategy))
                    .forEach(hits::add);
            if (hits.size() >= effectiveLimit) {
                break;
            }
        }
        return hits.stream().limit(effectiveLimit).toList();
    }

    /**
     * Builds corpus-side BM25 statistics once for an immutable textbook snapshot.
     *
     * <p>Tokenizing all 2,251 pages on every request dominated real cold-search latency while the corpus was unchanged.
     * OCR-empty pages keep empty statistics here, so they cannot win text search but remain eligible for the separate
     * CLIP page-image route.</p>
     */
    private PreparedCorpus preparedCorpus(List<TextbookChunk> chunks) {
        PreparedCorpus existing = preparedCorpora.get(chunks);
        if (existing != null) {
            return existing;
        }
        synchronized (preparedCorpora) {
            existing = preparedCorpora.get(chunks);
            if (existing != null) {
                return existing;
            }
            List<Map<String, Integer>> termFrequencies = new ArrayList<>(chunks.size());
            List<Integer> documentLengths = new ArrayList<>(chunks.size());
            List<Map<String, Integer>> titleTermFrequencies = new ArrayList<>(chunks.size());
            List<Integer> titleDocumentLengths = new ArrayList<>(chunks.size());
            List<String> compactMetadataTexts = new ArrayList<>(chunks.size());
            List<String> compactSurfaceTexts = new ArrayList<>(chunks.size());
            List<String> compactSectionTitles = new ArrayList<>(chunks.size());
            Map<String, Integer> documentFrequencies = new HashMap<>();
            Map<String, Integer> titleDocumentFrequencies = new HashMap<>();
            for (TextbookChunk chunk : chunks) {
                String surface = surfaceText(chunk);
                String sectionTitle = safe(chunk.sectionTitle());
                compactMetadataTexts.add(compact(metadataText(chunk)));
                compactSurfaceTexts.add(compact(surface));
                compactSectionTitles.add(compact(sectionTitle));
                Map<String, Integer> titleFrequencies = termFrequency(sectionTitle);
                titleTermFrequencies.add(Map.copyOf(titleFrequencies));
                titleDocumentLengths.add(titleFrequencies.values().stream().mapToInt(Integer::intValue).sum());
                titleFrequencies.keySet().forEach(term -> titleDocumentFrequencies.merge(term, 1, Integer::sum));
                if (!hasTextualEvidence(chunk)) {
                    termFrequencies.add(Map.of());
                    documentLengths.add(0);
                    continue;
                }
                Map<String, Integer> frequencies = termFrequency(surface);
                termFrequencies.add(Map.copyOf(frequencies));
                int length = frequencies.values().stream().mapToInt(Integer::intValue).sum();
                documentLengths.add(length);
                frequencies.keySet().forEach(term -> documentFrequencies.merge(term, 1, Integer::sum));
            }
            PreparedCorpus prepared = new PreparedCorpus(
                    List.copyOf(termFrequencies),
                    List.copyOf(documentLengths),
                    List.copyOf(titleTermFrequencies),
                    List.copyOf(titleDocumentLengths),
                    List.copyOf(compactMetadataTexts),
                    List.copyOf(compactSurfaceTexts),
                    List.copyOf(compactSectionTitles),
                    Map.copyOf(documentFrequencies),
                    Map.copyOf(titleDocumentFrequencies),
                    documentLengths.stream().mapToInt(Integer::intValue).average().orElse(1.0d),
                    titleDocumentLengths.stream().mapToInt(Integer::intValue).average().orElse(1.0d),
                    Map.copyOf(maxPageByDocId(chunks)));
            preparedCorpora.put(chunks, prepared);
            return prepared;
        }
    }

    private static double bm25Score(
            Map<String, Long> queryCounts,
            Map<String, Integer> termFrequencies,
            Map<String, Integer> documentFrequencies,
            int documentLength,
            double averageLength,
            int documentCount) {
        double score = 0.0d;
        for (Map.Entry<String, Long> queryTerm : queryCounts.entrySet()) {
            int frequency = termFrequencies.getOrDefault(queryTerm.getKey(), 0);
            if (frequency <= 0) {
                continue;
            }
            int documentFrequency = documentFrequencies.getOrDefault(queryTerm.getKey(), 0);
            if (documentFrequency <= 0) {
                continue;
            }
            double idf = Math.log(1.0d + (documentCount - documentFrequency + 0.5d) / (documentFrequency + 0.5d));
            double denominator = frequency + K1 * (1.0d - B + B * documentLength / Math.max(averageLength, 1e-9d));
            score += idf * (frequency * (K1 + 1.0d) / Math.max(denominator, 1e-9d)) * Math.min(queryTerm.getValue(), 3L);
        }
        return score;
    }

    /**
     * Keep document ordering lexical-first so the right book enters stage two. Structural matches only break ties
     * between books that are already positive on BM25.
     */
    private static Comparator<DocumentSignal> documentSignalComparator() {
        return Comparator.comparingDouble(DocumentSignal::bestLexicalScore).reversed()
                .thenComparing(Comparator.comparingInt(DocumentSignal::exactPageCount).reversed())
                .thenComparing(Comparator.comparingInt(DocumentSignal::metadataMatches).reversed())
                .thenComparing(Comparator.comparingInt(DocumentSignal::graphMatches).reversed())
                .thenComparing(Comparator.comparingInt(DocumentSignal::contentPageCount).reversed())
                .thenComparing(DocumentSignal::docId);
    }

    /**
     * Page ordering also stays lexical-first. Page quality is only a tie-breaker so noisy cover/appendix pages stop
     * stealing rank when they match only a generic token like "数学".
     */
    private static Comparator<PageSignal> pageSignalComparator() {
        return Comparator.comparingDouble(PageSignal::lexicalScore).reversed()
                .thenComparing(Comparator.comparing(PageSignal::exactQueryMatch).reversed())
                .thenComparing(Comparator.comparingInt(PageSignal::metadataMatches).reversed())
                .thenComparing(Comparator.comparingInt(PageSignal::graphMatches).reversed())
                .thenComparing(Comparator.comparingInt((PageSignal signal) -> pageQualityPriority(signal.qualityLabel())).reversed())
                .thenComparing(signal -> signal.chunk().docId())
                .thenComparingInt(signal -> signal.chunk().pageNo());
    }

    private static DocumentSignal toDocumentSignal(String docId, List<PageSignal> pages) {
        List<PageSignal> rankedPages = pages.stream().sorted(pageSignalComparator()).toList();
        return new DocumentSignal(
                docId,
                rankedPages.isEmpty() ? 0.0d : rankedPages.getFirst().lexicalScore(),
                (int) rankedPages.stream().filter(PageSignal::exactQueryMatch).count(),
                rankedPages.stream().mapToInt(PageSignal::metadataMatches).max().orElse(0),
                rankedPages.stream().mapToInt(PageSignal::graphMatches).max().orElse(0),
                (int) rankedPages.stream().filter(signal -> "content_page".equals(signal.qualityLabel())).count(),
                rankedPages);
    }

    private static int pageQualityPriority(String label) {
        return switch (label) {
            case "content_page" -> 4;
            case "toc_or_preface" -> 3;
            case "numeric_appendix" -> 2;
            case "cover_or_backmatter" -> 1;
            default -> 0;
        };
    }

    private static Set<String> queryGraphTerms(
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph,
            List<String> queryTerms) {
        if (queryGraph == null || queryGraph.empty()) {
            return Set.of();
        }
        Set<String> seen = new HashSet<>(queryTerms);
        LinkedHashMap<String, Boolean> ordered = new LinkedHashMap<>();
        for (String tag : queryGraph.expandedTagNames()) {
            List<String> tagTerms = terms(tag);
            boolean allSeen = !tagTerms.isEmpty() && tagTerms.stream().allMatch(seen::contains);
            if (!allSeen && tag != null && !tag.isBlank()) {
                ordered.put(compact(tag), Boolean.TRUE);
            }
        }
        return ordered.keySet();
    }

    private static int lexicalMatchCount(String compactHaystack, Iterable<String> terms) {
        if (compactHaystack.isBlank()) {
            return 0;
        }
        int matches = 0;
        for (String term : terms) {
            String normalized = compact(term);
            if (!normalized.isBlank() && compactHaystack.contains(normalized)) {
                matches += 1;
            }
        }
        return matches;
    }

    private static Map<String, Integer> maxPageByDocId(List<TextbookChunk> chunks) {
        Map<String, Integer> maxPages = new HashMap<>();
        for (TextbookChunk chunk : chunks) {
            maxPages.merge(chunk.docId(), chunk.pageNo(), Math::max);
        }
        return maxPages;
    }

    private static Map<String, Integer> termFrequency(String text) {
        Map<String, Integer> frequency = new LinkedHashMap<>();
        for (String term : terms(text)) {
            frequency.merge(term, 1, Integer::sum);
        }
        return frequency;
    }

    private static List<String> terms(String text) {
        String compact = compact(text);
        if (compact.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        Matcher matcher = ASCII_TERM.matcher(compact);
        while (matcher.find()) {
            String match = matcher.group();
            if (match.length() >= 2) {
                values.add(match);
            }
        }
        for (int width = 2; width <= 4; width += 1) {
            if (compact.length() < width) {
                continue;
            }
            for (int index = 0; index <= compact.length() - width; index += 1) {
                values.add(compact.substring(index, index + width));
            }
        }
        return values;
    }

    private static Map<String, Long> frequency(List<String> terms) {
        Map<String, Long> frequency = new LinkedHashMap<>();
        for (String term : terms) {
            frequency.merge(term, 1L, Long::sum);
        }
        return frequency;
    }

    private static String metadataText(TextbookChunk chunk) {
        return String.join("\n",
                safe(chunk.bookName()),
                String.join(" / ", nullToEmpty(chunk.chapterPath())),
                safe(chunk.sectionTitle()));
    }

    private static String surfaceText(TextbookChunk chunk) {
        return String.join("\n",
                metadataText(chunk),
                safe(chunk.text()),
                safe(chunk.formulaText()));
    }

    /**
     * Keep text retrieval focused on pages that actually carry textual or formula evidence.
     *
     * <p>Some processed textbook pages only expose a chapter heading plus an OCR-missing placeholder. Those pages are
     * still useful for image retrieval, but they should not outrank real content pages in the text RAG path.</p>
     */
    private static boolean hasTextualEvidence(TextbookChunk chunk) {
        return meaningfulBodyEvidence(chunk.text()) || meaningfulBodyEvidence(chunk.formulaText());
    }

    private static boolean meaningfulBodyEvidence(String value) {
        String normalized = normalizeSnippetText(extractBodyText(value));
        if (normalized.isBlank()) {
            return false;
        }
        String compact = compact(normalized);
        for (String marker : EMPTY_PAGE_MARKERS) {
            if (compact.contains(compact(marker))) {
                String withoutMarker = compact;
                for (String candidate : EMPTY_PAGE_MARKERS) {
                    withoutMarker = withoutMarker.replace(compact(candidate), "");
                }
                return withoutMarker.length() >= 24 && containsCjk(withoutMarker);
            }
        }
        return normalized.length() >= 16 && containsCjk(compact);
    }

    private static String extractBodyText(String value) {
        String normalized = safe(value);
        int bodyIndex = normalized.indexOf("## 正文");
        if (bodyIndex >= 0) {
            return normalized.substring(bodyIndex + "## 正文".length());
        }
        return normalized;
    }

    private static boolean containsCjk(String value) {
        for (int index = 0; index < value.length(); index += 1) {
            char current = value.charAt(index);
            if (current >= '\u4e00' && current <= '\u9fff') {
                return true;
            }
        }
        return false;
    }

    private static String compact(String text) {
        return String.valueOf(text == null ? "" : text)
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static TextbookSearchHit toHit(
            TextbookChunk chunk,
            double score,
            String pageQualityLabel,
            List<String> snippetTerms,
            String retrievalStrategy) {
        return new TextbookSearchHit(
                chunk.chunkId(),
                chunk.sectionId() == null || chunk.sectionId().isBlank() ? chunk.chunkId() : chunk.sectionId(),
                score,
                retrievalStrategy,
                chunk.docId(),
                chunk.bookName(),
                chunk.volume(),
                nullToEmpty(chunk.chapterPath()),
                chunk.pageNo(),
                chunk.printedPageNo(),
                chunk.sectionTitle(),
                relevantSnippet(chunk.text(), snippetTerms),
                chunk.formulaText(),
                chunk.imageRelPaths(),
                chunk.sourcePageImage(),
                pageQualityLabel,
                null);
    }

    /**
     * Preserve the strongest query-local evidence for long textbook pages.
     *
     * <p>The old fixed-prefix snippet frequently dropped the one sentence that distinguishes two sibling pages from
     * the same chapter. Stage two then compared two generic page heads and picked the wrong page even though the right
     * page had already been recalled. This helper keeps snippet extraction query-aware without hardcoding any topic or
     * benchmark phrase.</p>
     */
    private static String relevantSnippet(String text, List<String> snippetTerms) {
        String normalized = normalizeSnippetText(text);
        if (normalized.isBlank()) {
            return normalized;
        }
        if (snippetTerms == null || snippetTerms.isEmpty()) {
            return truncateSnippet(normalized);
        }
        List<TextSpan> spans = new ArrayList<>();
        for (String term : snippetTerms) {
            String needle = normalizeSnippetText(term);
            if (needle.isBlank()) {
                continue;
            }
            int fromIndex = 0;
            while (fromIndex < normalized.length()) {
                int matchIndex = normalized.indexOf(needle, fromIndex);
                if (matchIndex < 0) {
                    break;
                }
                spans.add(windowAroundMatch(normalized, matchIndex, needle.length()));
                if (spans.size() >= MAX_SNIPPET_WINDOWS) {
                    return mergeSnippetWindows(normalized, spans);
                }
                fromIndex = matchIndex + Math.max(1, needle.length());
            }
        }
        return spans.isEmpty() ? truncateSnippet(normalized) : mergeSnippetWindows(normalized, spans);
    }

    private static List<String> snippetTerms(
            String query,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
        LinkedHashMap<String, String> ordered = new LinkedHashMap<>();
        addSnippetTerms(ordered, queryClauses(query));
        if (queryGraph != null && !queryGraph.empty()) {
            addSnippetTerms(ordered, queryGraph.primaryTagNames());
            addSnippetTerms(ordered, queryGraph.expandedTagNames());
        }
        addSnippetTerms(ordered, terms(query));
        return ordered.entrySet().stream()
                .sorted(Map.Entry.<String, String>comparingByKey(Comparator.comparingInt(String::length).reversed())
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .toList();
    }

    private static List<String> queryClauses(String query) {
        String normalized = normalizeSnippetText(query);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> clauses = new ArrayList<>();
        for (String part : QUERY_CLAUSE_SPLITTER.split(normalized)) {
            String clause = normalizeSnippetText(part);
            if (clause.length() >= 4) {
                clauses.add(clause);
            }
        }
        return clauses;
    }

    private static void addSnippetTerms(Map<String, String> ordered, Iterable<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            String normalized = normalizeSnippetText(value);
            if (!shouldKeepSnippetTerm(normalized)) {
                continue;
            }
            ordered.putIfAbsent(compact(normalized), normalized);
        }
    }

    private static boolean shouldKeepSnippetTerm(String value) {
        if (value.isBlank()) {
            return false;
        }
        if (value.length() >= 4) {
            return true;
        }
        return value.length() >= 3 && value.chars().allMatch(LocalTextbookBm25SearchEngine::isAsciiWordChar);
    }

    private static boolean isAsciiWordChar(int codePoint) {
        return (codePoint >= '0' && codePoint <= '9')
                || (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z')
                || codePoint == '_';
    }

    private static TextSpan windowAroundMatch(String text, int matchIndex, int matchLength) {
        int start = Math.max(0, matchIndex - SNIPPET_WINDOW_RADIUS);
        int end = Math.min(text.length(), matchIndex + matchLength + SNIPPET_WINDOW_RADIUS);
        return new TextSpan(start, end);
    }

    private static String mergeSnippetWindows(String text, List<TextSpan> spans) {
        List<TextSpan> merged = spans.stream()
                .sorted(Comparator.comparingInt(TextSpan::start))
                .reduce(new ArrayList<TextSpan>(), (acc, span) -> {
                    if (acc.isEmpty()) {
                        acc.add(span);
                        return acc;
                    }
                    TextSpan last = acc.getLast();
                    if (span.start() <= last.end()) {
                        acc.set(acc.size() - 1, new TextSpan(last.start(), Math.max(last.end(), span.end())));
                        return acc;
                    }
                    acc.add(span);
                    return acc;
                }, (left, right) -> {
                    left.addAll(right);
                    return left;
                });
        StringBuilder builder = new StringBuilder();
        for (TextSpan span : merged) {
            if (!builder.isEmpty()) {
                builder.append(" … ");
            }
            builder.append(text, span.start(), span.end());
            if (builder.length() >= MAX_SNIPPET_CHARS) {
                break;
            }
        }
        return truncateSnippet(builder.toString());
    }

    private static String truncateSnippet(String text) {
        if (text.length() <= MAX_SNIPPET_CHARS) {
            return text;
        }
        return text.substring(0, MAX_SNIPPET_CHARS).strip() + "…";
    }

    private static String normalizeSnippetText(String text) {
        return safe(text).replaceAll("\\s+", " ").strip();
    }

    private static List<String> nullToEmpty(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static double roundScore(double score) {
        return Math.round(score * 10000.0d) / 10000.0d;
    }

    private record PageSignal(
            TextbookChunk chunk,
            double lexicalScore,
            boolean exactQueryMatch,
            int metadataMatches,
            int graphMatches,
            String qualityLabel) {
    }

    private record DocumentSignal(
            String docId,
            double bestLexicalScore,
            int exactPageCount,
            int metadataMatches,
            int graphMatches,
            int contentPageCount,
            List<PageSignal> pages) {
    }

    /** Immutable corpus-side state shared by every query against one textbook snapshot. */
    private record PreparedCorpus(
            List<Map<String, Integer>> termFrequencies,
            List<Integer> documentLengths,
            List<Map<String, Integer>> titleTermFrequencies,
            List<Integer> titleDocumentLengths,
            List<String> compactMetadataTexts,
            List<String> compactSurfaceTexts,
            List<String> compactSectionTitles,
            Map<String, Integer> documentFrequencies,
            Map<String, Integer> titleDocumentFrequencies,
            double averageLength,
            double titleAverageLength,
            Map<String, Integer> maxPageByDocId) {
    }

    private record TextSpan(int start, int end) {
    }
}
