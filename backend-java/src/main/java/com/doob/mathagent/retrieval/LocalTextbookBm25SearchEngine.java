package com.doob.mathagent.retrieval;

import com.doob.mathagent.resources.TextbookChunk;
import com.doob.mathagent.teacher.service.TeacherResourceGraphAlignmentService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
    private static final int DEFAULT_CANDIDATE_MULTIPLIER = 3;
    private static final int DEFAULT_CANDIDATE_FLOOR = 6;
    private static final Map<String, Double> FIELD_WEIGHTS = Map.of(
            "bookName", 0.45,
            "chapterPath", 1.25,
            "sectionTitle", 1.45,
            "text", 1.00,
            "formulaText", 0.95);
    private static final Pattern ASCII_TERM = Pattern.compile("[A-Za-z0-9_]+");
    private final TextbookPageQualityClassifier pageQualityClassifier;

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
     * Runs a local two-stage textbook retriever.
     *
     * <p>Stage one ranks candidate books/documents so generic method words do not let an unrelated page from another
     * book win too early. Stage two then reranks pages only inside those candidate books and adds a small neighbor
     * evidence boost so cross-page explanations are less likely to be truncated.</p>
     *
     * <p>The graph context is optional. When present, it only contributes normalized concept aliases from the
     * existing knowledge graph; do not add query-specific shortcuts here.</p>
     */
    public List<TextbookSearchHit> search(
            String query,
            List<TextbookChunk> chunks,
            int limit,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
        List<String> queryTerms = terms(query);
        if (queryTerms.isEmpty() || chunks.isEmpty()) {
            return List.of();
        }

        List<Map<String, Double>> documents = new ArrayList<>();
        List<Double> documentLengths = new ArrayList<>();
        Map<String, Integer> documentFrequencies = new HashMap<>();
        for (TextbookChunk chunk : chunks) {
            Map<String, Double> weightedTerms = weightedTerms(chunk);
            documents.add(weightedTerms);
            documentLengths.add(weightedTerms.values().stream().mapToDouble(Double::doubleValue).sum());
            weightedTerms.keySet().forEach(term -> documentFrequencies.merge(term, 1, Integer::sum));
        }

        double averageLength = documentLengths.stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
        Map<Integer, Double> scores = new HashMap<>();
        Map<String, Integer> maxPageByDocId = maxPageByDocId(chunks);
        Map<String, Long> queryCounts = frequency(queryTerms);
        int documentCount = chunks.size();
        Set<String> graphTerms = queryGraphTerms(queryGraph, queryTerms);
        Map<Integer, ChunkSignals> chunkSignals = new LinkedHashMap<>();
        for (Map.Entry<String, Long> queryTerm : queryCounts.entrySet()) {
            int documentFrequency = documentFrequencies.getOrDefault(queryTerm.getKey(), 0);
            if (documentFrequency == 0) {
                continue;
            }
            double idf = Math.log(1.0 + (documentCount - documentFrequency + 0.5) / (documentFrequency + 0.5));
            for (int index = 0; index < documents.size(); index++) {
                double termFrequency = documents.get(index).getOrDefault(queryTerm.getKey(), 0.0);
                if (termFrequency <= 0.0) {
                    continue;
                }
                double denominator = termFrequency + K1 * (1.0 - B + B * documentLengths.get(index) / Math.max(averageLength, 1e-9));
                double termScore = idf * (termFrequency * (K1 + 1.0) / Math.max(denominator, 1e-9));
                scores.merge(index, termScore * Math.min(queryTerm.getValue(), 3L), Double::sum);
            }
        }

        List<String> phrases = phraseTerms(query);
        String compactQuery = compact(query);
        for (int index = 0; index < chunks.size(); index++) {
            TextbookChunk chunk = chunks.get(index);
            String compactDocument = compact(entityText(chunk));
            String qualityLabel = pageQualityClassifier.label(chunk, maxPageByDocId.getOrDefault(chunk.docId(), 0));
            double lexical = scores.getOrDefault(index, 0.0d);
            double exact = !compactQuery.isBlank() && compactDocument.contains(compactQuery) ? 2.0d : 0.0d;
            double phrase = phraseScore(compactDocument, phrases);
            double metadata = metadataScore(chunk, compactQuery, queryTerms);
            double graph = graphScore(chunk, graphTerms);
            double qualityFactor = pageQualityClassifier.scoreFactor(qualityLabel);
            double coarse = (lexical + exact + phrase + metadata + graph) * qualityFactor;
            if (coarse > 0.0d) {
                chunkSignals.put(index, new ChunkSignals(chunk, coarse, lexical, metadata, graph, qualityLabel, qualityFactor));
            }
        }

        int effectiveLimit = limit > 0 ? limit : 10;
        List<DocumentCandidate> rankedDocuments = coarseDocumentCandidates(chunkSignals, effectiveLimit);
        Map<String, Double> bestCoarseByDoc = rankedDocuments.stream()
                .collect(LinkedHashMap::new,
                        (values, candidate) -> values.put(candidate.docId(), candidate.score()),
                        Map::putAll);
        Map<String, Map<Integer, ChunkSignals>> signalIndexByDoc = signalsByDoc(chunkSignals);
        return rerankedChunkHits(rankedDocuments, signalIndexByDoc, bestCoarseByDoc, effectiveLimit)
                .toList();
    }

    private static Map<String, Map<Integer, ChunkSignals>> signalsByDoc(Map<Integer, ChunkSignals> chunkSignals) {
        Map<String, Map<Integer, ChunkSignals>> byDoc = new LinkedHashMap<>();
        for (Map.Entry<Integer, ChunkSignals> entry : chunkSignals.entrySet()) {
            byDoc.computeIfAbsent(entry.getValue().chunk().docId(), ignored -> new LinkedHashMap<>())
                    .put(entry.getKey(), entry.getValue());
        }
        return byDoc;
    }

    private static List<DocumentCandidate> coarseDocumentCandidates(
            Map<Integer, ChunkSignals> chunkSignals,
            int effectiveLimit) {
        Map<String, List<ChunkSignals>> byDoc = new LinkedHashMap<>();
        for (ChunkSignals signals : chunkSignals.values()) {
            byDoc.computeIfAbsent(signals.chunk().docId(), ignored -> new ArrayList<>()).add(signals);
        }
        int candidateLimit = Math.min(
                byDoc.size(),
                Math.max(effectiveLimit * DEFAULT_CANDIDATE_MULTIPLIER, DEFAULT_CANDIDATE_FLOOR));
        return byDoc.entrySet().stream()
                .map(entry -> new DocumentCandidate(
                        entry.getKey(),
                        documentScore(entry.getValue()),
                        entry.getValue().stream()
                                .map(ChunkSignals::chunk)
                                .sorted(Comparator.comparingInt(TextbookChunk::pageNo))
                                .toList()))
                .sorted(Comparator.comparingDouble(DocumentCandidate::score).reversed()
                        .thenComparing(DocumentCandidate::docId))
                .limit(candidateLimit)
                .toList();
    }

    private static double documentScore(List<ChunkSignals> signals) {
        List<ChunkSignals> ranked = signals.stream()
                .sorted(Comparator.comparingDouble(ChunkSignals::coarseScore).reversed())
                .toList();
        double best = ranked.isEmpty() ? 0.0d : ranked.getFirst().coarseScore();
        double second = ranked.size() > 1 ? ranked.get(1).coarseScore() : 0.0d;
        double graph = ranked.stream().mapToDouble(ChunkSignals::graphScore).max().orElse(0.0d);
        double metadata = ranked.stream().mapToDouble(ChunkSignals::metadataScore).max().orElse(0.0d);
        double lexical = ranked.stream().mapToDouble(ChunkSignals::lexicalScore).max().orElse(0.0d);
        long contentPages = ranked.stream()
                .filter(signal -> "content_page".equals(signal.qualityLabel()))
                .count();
        return best * 1.45d
                + second * 0.65d
                + lexical * 0.55d
                + metadata * 0.8d
                + graph * 0.9d
                + Math.min(contentPages, 3L) * 0.25d;
    }

    private static java.util.stream.Stream<TextbookSearchHit> rerankedChunkHits(
            List<DocumentCandidate> rankedDocuments,
            Map<String, Map<Integer, ChunkSignals>> signalIndexByDoc,
            Map<String, Double> bestCoarseByDoc,
            int effectiveLimit) {
        return rankedDocuments.stream()
                .flatMap(document -> document.chunks().stream()
                        .map(chunk -> {
                            ChunkSignals signals = signalIndexByDoc.getOrDefault(document.docId(), Map.of()).values().stream()
                                    .filter(candidate -> candidate.chunk().chunkId().equals(chunk.chunkId()))
                                    .findFirst()
                                    .orElse(null);
                            if (signals == null) {
                                return null;
                            }
                            double score = bestCoarseByDoc.getOrDefault(document.docId(), 0.0d) * 0.18d
                                    + signals.coarseScore() * 1.55d
                                    + neighborEvidenceBoost(chunk, signalIndexByDoc.getOrDefault(document.docId(), Map.of()));
                            return toHit(chunk, roundScore(score), signals.qualityLabel());
                        }))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingDouble(TextbookSearchHit::score).reversed()
                        .thenComparing(TextbookSearchHit::docId)
                        .thenComparingInt(TextbookSearchHit::pageNo))
                .limit(effectiveLimit);
    }

    private static double neighborEvidenceBoost(TextbookChunk chunk, Map<Integer, ChunkSignals> signalsByIndex) {
        double boost = 0.0d;
        for (ChunkSignals signals : signalsByIndex.values()) {
            int distance = Math.abs(signals.chunk().pageNo() - chunk.pageNo());
            if (distance == 0 || distance > 1) {
                continue;
            }
            boost += signals.coarseScore() * 0.18d / distance;
        }
        return Math.min(boost, 1.2d);
    }

    private static double phraseScore(String compactDocument, List<String> phrases) {
        double score = 0.0d;
        for (String phrase : phrases) {
            if (compactDocument.contains(compact(phrase))) {
                score += Math.min(1.2d, 0.18d + compact(phrase).length() * 0.035d);
            }
        }
        return score;
    }

    private static double metadataScore(TextbookChunk chunk, String compactQuery, List<String> queryTerms) {
        double score = 0.0d;
        score += fieldScore(chunk.bookName(), compactQuery, queryTerms, 0.40d, 0.10d);
        score += fieldScore(String.join(" ", nullToEmpty(chunk.chapterPath())), compactQuery, queryTerms, 1.35d, 0.28d);
        score += fieldScore(chunk.sectionTitle(), compactQuery, queryTerms, 1.55d, 0.32d);
        return score;
    }

    private static double graphScore(TextbookChunk chunk, Set<String> graphTerms) {
        if (graphTerms.isEmpty()) {
            return 0.0d;
        }
        String metadata = compact(String.join(" ",
                safe(chunk.bookName()),
                String.join(" ", nullToEmpty(chunk.chapterPath())),
                safe(chunk.sectionTitle()),
                safe(chunk.text())));
        double score = 0.0d;
        for (String term : graphTerms) {
            String normalized = compact(term);
            if (normalized.isBlank()) {
                continue;
            }
            if (metadata.contains(normalized)) {
                score += Math.min(0.9d, 0.18d + normalized.length() * 0.03d);
            }
        }
        return Math.min(score, 2.4d);
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
                ordered.put(tag, Boolean.TRUE);
            }
        }
        return ordered.keySet();
    }

    private static double fieldScore(
            String text,
            String compactQuery,
            List<String> queryTerms,
            double exactBoost,
            double termBoost) {
        String compactField = compact(text);
        if (compactField.isBlank()) {
            return 0.0d;
        }
        double score = 0.0d;
        if (!compactQuery.isBlank() && compactField.contains(compactQuery)) {
            score += exactBoost;
        }
        for (String term : queryTerms) {
            if (term.length() >= 3 && compactField.contains(term)) {
                score += termBoost;
            }
        }
        return score;
    }

    private static Map<String, Integer> maxPageByDocId(List<TextbookChunk> chunks) {
        Map<String, Integer> maxPages = new HashMap<>();
        for (TextbookChunk chunk : chunks) {
            maxPages.merge(chunk.docId(), chunk.pageNo(), Math::max);
        }
        return maxPages;
    }

    private static Map<String, Double> weightedTerms(TextbookChunk chunk) {
        Map<String, Double> weighted = new HashMap<>();
        addWeightedTerms(weighted, chunk.bookName(), FIELD_WEIGHTS.get("bookName"));
        addWeightedTerms(weighted, String.join(" / ", nullToEmpty(chunk.chapterPath())), FIELD_WEIGHTS.get("chapterPath"));
        addWeightedTerms(weighted, chunk.sectionTitle(), FIELD_WEIGHTS.get("sectionTitle"));
        addWeightedTerms(weighted, chunk.text(), FIELD_WEIGHTS.get("text"));
        addWeightedTerms(weighted, chunk.formulaText(), FIELD_WEIGHTS.get("formulaText"));
        return weighted;
    }

    private static void addWeightedTerms(Map<String, Double> target, String text, double weight) {
        for (String term : terms(text)) {
            target.merge(term, weight, Double::sum);
        }
    }

    private static List<String> terms(String text) {
        String compact = compact(text);
        if (compact.isBlank()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        Matcher matcher = ASCII_TERM.matcher(compact);
        while (matcher.find()) {
            String match = matcher.group();
            if (match.length() >= 2) {
                terms.add(match);
            }
        }
        for (int width = 2; width <= 4; width++) {
            if (compact.length() >= width) {
                for (int index = 0; index <= compact.length() - width; index++) {
                    terms.add(compact.substring(index, index + width));
                }
            }
        }
        return terms;
    }

    private static List<String> phraseTerms(String text) {
        return terms(text).stream()
                .filter(term -> term.length() >= 3)
                .distinct()
                .toList();
    }

    private static Map<String, Long> frequency(List<String> terms) {
        Map<String, Long> frequency = new LinkedHashMap<>();
        for (String term : terms) {
            frequency.merge(term, 1L, Long::sum);
        }
        return frequency;
    }

    private static String compact(String text) {
        return String.valueOf(text == null ? "" : text)
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String entityText(TextbookChunk chunk) {
        return String.join("\n",
                safe(chunk.bookName()),
                String.join(" / ", nullToEmpty(chunk.chapterPath())),
                safe(chunk.sectionTitle()),
                safe(chunk.text()),
                safe(chunk.formulaText()));
    }

    private static TextbookSearchHit toHit(TextbookChunk chunk, double score, String pageQualityLabel) {
        return new TextbookSearchHit(
                chunk.chunkId(),
                score,
                "local_two_stage_doc_page",
                chunk.docId(),
                chunk.bookName(),
                chunk.volume(),
                nullToEmpty(chunk.chapterPath()),
                chunk.pageNo(),
                chunk.printedPageNo(),
                chunk.sectionTitle(),
                snippet(chunk.text()),
                chunk.formulaText(),
                chunk.sourcePageImage(),
                pageQualityLabel);
    }

    private static String snippet(String text) {
        String value = safe(text).strip();
        return value.length() <= 180 ? value : value.substring(0, 180);
    }

    private static List<String> nullToEmpty(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static double roundScore(double score) {
        return Math.round(score * 10000.0) / 10000.0;
    }

    private record ChunkSignals(
            TextbookChunk chunk,
            double coarseScore,
            double lexicalScore,
            double metadataScore,
            double graphScore,
            String qualityLabel,
            double qualityFactor) {
    }

    private record DocumentCandidate(
            String docId,
            double score,
            List<TextbookChunk> chunks) {
    }
}
