package com.doob.mathagent.retrieval;

import com.doob.mathagent.resources.TextbookChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LocalTextbookBm25SearchEngine {

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final Map<String, Double> FIELD_WEIGHTS = Map.of(
            "bookName", 0.45,
            "chapterPath", 1.25,
            "sectionTitle", 1.45,
            "text", 1.00,
            "formulaText", 0.95);
    private static final Pattern ASCII_TERM = Pattern.compile("[A-Za-z0-9_]+");

    public List<TextbookSearchHit> search(String query, List<TextbookChunk> chunks, int limit) {
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
        Map<String, Long> queryCounts = frequency(queryTerms);
        int documentCount = chunks.size();
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

        String compactQuery = compact(query);
        for (Integer index : new ArrayList<>(scores.keySet())) {
            String compactDocument = compact(entityText(chunks.get(index)));
            if (!compactQuery.isBlank() && compactDocument.contains(compactQuery)) {
                scores.merge(index, 2.0, Double::sum);
            }
            for (String phrase : phraseTerms(query)) {
                if (compactDocument.contains(compact(phrase))) {
                    scores.merge(index, Math.min(1.2, 0.18 + compact(phrase).length() * 0.035), Double::sum);
                }
            }
        }

        int effectiveLimit = limit > 0 ? limit : 10;
        return scores.entrySet().stream()
                .filter(entry -> entry.getValue() > 0.0)
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .limit(effectiveLimit)
                .map(entry -> toHit(chunks.get(entry.getKey()), roundScore(entry.getValue())))
                .toList();
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

    private static TextbookSearchHit toHit(TextbookChunk chunk, double score) {
        return new TextbookSearchHit(
                chunk.chunkId(),
                score,
                "local_bm25",
                chunk.docId(),
                chunk.bookName(),
                chunk.volume(),
                nullToEmpty(chunk.chapterPath()),
                chunk.pageNo(),
                chunk.printedPageNo(),
                chunk.sectionTitle(),
                snippet(chunk.text()),
                chunk.formulaText(),
                chunk.sourcePageImage());
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
}
