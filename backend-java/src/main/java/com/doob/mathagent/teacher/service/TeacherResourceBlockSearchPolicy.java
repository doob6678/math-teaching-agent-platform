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
import com.doob.mathagent.teacher.search.TeacherResourceSearchProperties;
import com.doob.mathagent.teacher.search.TeacherResourceSearchFilter;
import com.doob.mathagent.teacher.search.audit.TeacherResourceBlockSearchAuditEvent;
import com.doob.mathagent.teacher.search.audit.TeacherResourceBlockSearchAuditSink;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.document.TeacherResourceReadiness;
import com.doob.mathagent.teacher.document.TeacherResourceVisibilityPolicy;
import com.doob.mathagent.teacher.support.TeacherResourceLibraryResolver;
import com.doob.mathagent.vector.service.VectorIndexService;
import com.doob.mathagent.vector.service.VectorSearchFilter;
import com.doob.mathagent.vector.service.VectorSearchHit;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService.BlockContext;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService.DocumentCandidate;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService.BlockCandidate;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService.EvidenceWindow;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService.StageTwoBlockCandidate;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService.MergeCandidate;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService.FocusedSearchQuery;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService.VectorCoarseRecall;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService.CanonicalCandidate;
import static com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService.*;

/**
 * Pure search policy extracted from the resource search facade.
 * Query normalization, candidate scoring, and evidence shaping are stateless and independently testable here.
 */
final class TeacherResourceBlockSearchPolicy {
    private static final int NEGATION_WINDOW_CHARS = 18;
    private static final Map<String, List<String>> ROLE_CUES = Map.of(
            "analysis", List.of("解析", "分析", "solution", "analysis"),
            "lesson", List.of("专题讲解", "专题讲评", "讲评课", "整体讲法", "lesson", "course", "课堂"),
            "question", List.of("题面", "原题", "真题", "单题", "question", "prompt", "stem"),
            "boardwork", List.of("板书", "boardwork", "blackboard"),
            "method", List.of("方法", "method", "approach"));
    private static final Set<String> QUERY_STOP_WORDS = Set.of(
            "a", "an", "and", "about", "are", "as", "at", "by", "for", "from", "how", "in", "is", "it",
            "need", "not", "of", "on", "or", "reference", "the", "this", "to", "with", "teacher", "student");

    private TeacherResourceBlockSearchPolicy() {
        // Stateless policy component.
    }


    /** Scores only same-source metadata; it deliberately does not use a mutable display title as an identity. */
    static int sourceAffinity(
            String mirrorSourceIdentity,
            String mirrorSourcePath,
            Set<String> mirrorTokens,
            TeacherResourceDocumentResponse candidateDocument,
            List<TeacherDocumentBlockResponse> candidateBlocks) {
        if (!mirrorSourceIdentity.isBlank()
                && mirrorSourceIdentity.equals(textOrDefault(candidateDocument.sourceIdentity(), ""))) {
            return 3;
        }
        boolean sameSourcePath = !mirrorSourcePath.isBlank() && candidateBlocks.stream()
                .anyMatch(block -> mirrorSourcePath.equals(textOrDefault(block.sourcePath(), "")));
        if (sameSourcePath) {
            return 2;
        }
        Set<String> candidateTokens = stableSourceTokens(
                candidateDocument.title(), candidateDocument.originalUrl(), candidateDocument.sourceIdentity());
        return mirrorTokens.stream().anyMatch(candidateTokens::contains) ? 1 : 0;
    }


    /**
     * Requires a strong same-block anchor after the source has been correlated.  A reused section title alone is
     * intentionally insufficient because many teacher collections have sections named “例题” or “方法”.
     */
    static int blockAffinity(
            TeacherResourceBlockSearchResponse.Hit hit,
            String mirrorSourcePath,
            String mirrorSection,
            String mirrorChecksum,
            String mirrorText,
            TeacherDocumentBlockResponse candidateBlock) {
        boolean sameChecksum = !mirrorChecksum.isBlank()
                && mirrorChecksum.equals(textOrDefault(candidateBlock.checksum(), ""));
        boolean sameSourcePath = !mirrorSourcePath.isBlank()
                && mirrorSourcePath.equals(textOrDefault(candidateBlock.sourcePath(), ""));
        boolean sameSection = sameReferenceText(mirrorSection, candidateBlock.section());
        boolean sameText = sameReferenceText(mirrorText, candidateBlock.normalizedText())
                || sameReferenceText(mirrorText, candidateBlock.rawText());
        boolean sameExternalBlockId = mirrorBlockExternalIdMatches(hit, candidateBlock);
        if (!sameChecksum
                && !(sameSourcePath && sameSection)
                && !(sameSection && sameText)
                && !sameExternalBlockId) {
            return 0;
        }
        int score = 0;
        if (sameChecksum) {
            score += 16;
        }
        if (sameSourcePath) {
            score += 8;
        }
        if (sameSection) {
            score += 4;
        }
        if (sameText) {
            score += 3;
        }
        if (sameExternalBlockId) {
            score += 12;
        }
        return score;
    }


    static boolean mirrorBlockExternalIdMatches(
            TeacherResourceBlockSearchResponse.Hit hit,
            TeacherDocumentBlockResponse candidateBlock) {
        return hit.blockId().equals(candidateBlock.blockId())
                || hit.blockId().equals(textOrDefault(candidateBlock.externalBlockId(), ""));
    }


    /** Removes formatting-only differences before comparing synced Markdown blocks. */
    static boolean sameReferenceText(String left, String right) {
        String normalizedLeft = normalizeText(textOrDefault(left, "")).replaceAll("[\\p{Punct}，。；：！？、】【（）\\s]+", "");
        String normalizedRight = normalizeText(textOrDefault(right, "")).replaceAll("[\\p{Punct}，。；：！？、】【（）\\s]+", "");
        return !normalizedLeft.isBlank() && normalizedLeft.equals(normalizedRight);
    }


    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }


    static Set<String> stableSourceTokens(String... values) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String value : values) {
            Matcher matcher = STABLE_SOURCE_TOKEN.matcher(textOrDefault(value, ""));
            while (matcher.find()) {
                tokens.add(matcher.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(tokens);
    }


    /** Matches meaningful focused terms against a real source title for deterministic lexical admission. */
    static boolean titleMatchesQuery(String title, String normalizedQuery, String[] focusedTerms) {
        String normalizedTitle = normalizeQuery(title);
        if (normalizedTitle.isBlank()) {
            return false;
        }
        if (!normalizedQuery.isBlank() && normalizedTitle.contains(normalizedQuery)) {
            return true;
        }
        if (focusedTerms == null) {
            return false;
        }
        for (String term : focusedTerms) {
            String normalizedTerm = normalizeQuery(term);
            if (normalizedTerm.length() >= MIN_TITLE_RECALL_TERM_LENGTH && normalizedTitle.contains(normalizedTerm)) {
                return true;
            }
        }
        return false;
    }


    static boolean isExplicitMixedLibraryFilter(TeacherResourceSearchFilter filter) {
        if (filter == null || filter.sourceTypes() == null || filter.sourceTypes().isEmpty()) {
            return false;
        }
        boolean includesTextbook = filter.sourceTypes().stream()
                .map(TeacherResourceBlockSearchService::normalizeText)
                .anyMatch(selector -> "textbook".equals(selector) || "public_textbook".equals(selector));
        return includesTextbook && filter.sourceTypes().stream()
                .map(TeacherResourceBlockSearchService::normalizeText)
                .anyMatch(selector -> !"textbook".equals(selector) && !"public_textbook".equals(selector));
    }


    static List<TeacherResourceBlockSearchResponse.Hit> applyCrossSourceQuota(
            List<TeacherResourceBlockSearchResponse.Hit> rankedHits,
            int limit) {
        int boundedLimit = Math.max(1, limit);
        int teacherQuota = (boundedLimit + 1) / 2;
        int textbookQuota = boundedLimit - teacherQuota;
        List<TeacherResourceBlockSearchResponse.Hit> selected = new ArrayList<>(boundedLimit);
        int teacherCount = 0;
        int textbookCount = 0;
        for (TeacherResourceBlockSearchResponse.Hit hit : rankedHits) {
            boolean textbook = "public_textbook".equals(normalizeText(hit.sourceType()));
            if ((textbook && textbookCount >= textbookQuota) || (!textbook && teacherCount >= teacherQuota)) {
                continue;
            }
            selected.add(hit);
            if (textbook) {
                textbookCount++;
            } else {
                teacherCount++;
            }
        }
        // A source with fewer candidates must not leave result slots empty; retain global rerank order for the remainder.
        for (TeacherResourceBlockSearchResponse.Hit hit : rankedHits) {
            if (selected.size() >= boundedLimit) {
                break;
            }
            if (!selected.contains(hit)) {
                selected.add(hit);
            }
        }
        return List.copyOf(selected);
    }



    static String buildTextbookMergeMode(
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
     * Stage-one document order is the Milvus semantic coarse-recall order. Lexical, graph, and role data only admit
     * fallback candidates or enrich the later rerank payload; they do not create an opaque weighted score cocktail.
     */
    static Comparator<DocumentCandidate> documentCandidateComparator() {
        return Comparator.comparingDouble(DocumentCandidate::coarseScore).reversed();
    }


    static Comparator<BlockContext> blockSupportComparator(
            TeacherResourceDocumentResponse document,
            Map<String, Double> vectorScoreByKey,
            String normalizedQuery,
            String[] terms) {
        return Comparator.<BlockContext>comparingDouble(
                        block -> vectorScoreByKey.getOrDefault(blockKey(document.documentId(), block.block().blockId()), 0.0d))
                .reversed()
                .thenComparing(Comparator.comparingInt(
                        (BlockContext block) -> blockLexicalMatchCount(document, block, normalizedQuery, terms)).reversed())
                .thenComparing(block -> block.block().blockOrder());
    }


    static Comparator<BlockContext> semanticFallbackBlockComparator(
            TeacherResourceDocumentResponse document,
            Map<String, Double> semanticScoreByKey,
            String normalizedQuery,
            String[] terms) {
        return Comparator.<BlockContext>comparingDouble(
                        block -> semanticScoreByKey.getOrDefault(blockKey(document.documentId(), block.block().blockId()), 0.0d))
                .reversed()
                .thenComparing(Comparator.comparingInt(
                        (BlockContext block) -> blockLexicalMatchCount(document, block, normalizedQuery, terms)).reversed())
                .thenComparing(block -> block.block().blockOrder());
    }


    /**
     * Legacy role-bucket heuristics were intentionally removed here. The previous implementation tried to infer
     * "analysis/question/lesson" intent from hand-written cue lists and then override the semantic ranking. That made
     * retrieval behavior brittle and benchmark-sensitive. The rewritten pipeline keeps blockRole/sourcePath/chapter/
     * section and the adjacent evidence window inside the rerank text itself, so the real rerank model stays primary
     * while lexical and graph signals only break ties.
     */
    static Comparator<BlockCandidate> blockCandidateComparator() {
        Comparator<BlockCandidate> comparator = Comparator.comparingInt(BlockCandidate::roleIntentScore).reversed();
        comparator = comparator.thenComparing(Comparator.comparingDouble(BlockCandidate::rerankScore).reversed());
        comparator = comparator.thenComparing(Comparator.comparingDouble(BlockCandidate::documentCoarseScore).reversed());
        comparator = comparator.thenComparing(Comparator.comparingDouble(BlockCandidate::vectorSemanticScore).reversed());
        return comparator.thenComparing(Comparator.comparingInt(BlockCandidate::lexicalMatches).reversed());
    }

    /**
     * Extracts a bounded role preference from the user's routing language. Explicit exclusions are hard ordering
     * signals, while an absent role cue leaves the semantic reranker fully in charge.
     */
    static int roleIntentScore(String query, BlockContext block) {
        String normalizedQuery = normalizeText(textOrDefault(query, ""));
        String candidateRole = normalizeText(String.join(
                " ",
                textOrDefault(block.blockRole(), ""),
                textOrDefault(block.block().chapter(), ""),
                textOrDefault(block.block().section(), ""),
                textOrDefault(block.sourcePath(), "")));
        int score = 0;
        for (Map.Entry<String, List<String>> entry : ROLE_CUES.entrySet()) {
            boolean candidateHasRole = entry.getValue().stream().anyMatch(candidateRole::contains);
            if (!candidateHasRole) {
                continue;
            }
            boolean positiveCue = entry.getValue().stream()
                    .anyMatch(cue -> normalizedQuery.contains(cue) && !isNegatedCue(normalizedQuery, cue));
            boolean negativeCue = entry.getValue().stream().anyMatch(cue -> isNegatedCue(normalizedQuery, cue));
            if (positiveCue) {
                score += 1;
            }
            if (negativeCue) {
                score -= 2;
            }
        }
        return score;
    }

    /** Detects Chinese and English exclusion wording immediately before one role cue. */
    private static boolean isNegatedCue(String query, String cue) {
        int cueStart = query.indexOf(cue);
        while (cueStart >= 0) {
            int start = Math.max(0, cueStart - NEGATION_WINDOW_CHARS);
            String prefix = query.substring(start, cueStart);
            int end = Math.min(query.length(), cueStart + cue.length() + NEGATION_WINDOW_CHARS);
            String suffix = query.substring(cueStart + cue.length(), end);
            if (containsAny(prefix, "不要", "不能", "不是", "而不是", "排除", "拒绝", "not", "don't", "without", "instead of")
                    || startsWithAny(suffix.strip(), "不要", "不能", "也不要", "也不能", "排除", "拒绝", "not ", "don't ", "without ")) {
                return true;
            }
            cueStart = query.indexOf(cue, cueStart + cue.length());
        }
        return false;
    }

    /** Accepts only a negation immediately following a cue, avoiding a later clause negating an earlier positive cue. */
    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }


    static int blockLexicalMatchCount(
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


    static int lexicalMatchCount(String haystack, String normalizedQuery, String[] terms) {
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


    static BlockContext toContext(
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
     * Translates one textbook page hit into the teacher-search hit contract so callers can keep one response parser.
     * The synthetic sourcePath is stable and clearly marks that this evidence came from processed_books rather than a
     * teacher-uploaded document row.
     */
    static TeacherResourceBlockSearchResponse.Hit textbookHit(TextbookSearchHit hit) {
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
    static TeacherResourceBlockSearchResponse.Hit textbookImageHit(TextbookPageImageSearchHit hit) {
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


    static TeacherResourceBlockSearchResponse.Hit preferMergeHit(
            TeacherResourceBlockSearchResponse.Hit left,
            TeacherResourceBlockSearchResponse.Hit right) {
        int leftEvidenceLength = textOrDefault(left.evidenceText(), left.snippet()).length();
        int rightEvidenceLength = textOrDefault(right.evidenceText(), right.snippet()).length();

        if (rightEvidenceLength > leftEvidenceLength) {
            return right.score() >= left.score() ? right : withScore(right, left.score());
        }
        return left.score() >= right.score() ? left : withScore(left, right.score());
    }


    static TeacherResourceBlockSearchResponse.Hit withScore(
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
     * Adds image ids from the nearest parsed sibling block when the ranked text block itself has no image.
     * Same-page images win for paged sources; block distance is the deterministic fallback for Feishu/DOCX content.
     */
    static TeacherResourceBlockSearchResponse.Hit enrichNearbyImageAssets(
            TeacherResourceBlockSearchResponse.Hit hit,
            Map<String, List<TeacherDocumentBlockResponse>> blocksByDocument) {
        if (hit == null || hit.imageAssetIds() != null && !hit.imageAssetIds().isEmpty()) {
            return hit;
        }
        List<TeacherDocumentBlockResponse> blocks = blocksByDocument.getOrDefault(hit.documentId(), List.of());
        List<String> nearbyAssetIds = blocks.stream()
                .filter(block -> "active".equalsIgnoreCase(block.status()))
                .filter(block -> Math.abs(block.blockOrder() - hit.blockOrder()) <= MAX_NEARBY_IMAGE_BLOCK_DISTANCE)
                .filter(block -> !parseImageAssetIds(block.imageRefs()).isEmpty())
                .sorted(Comparator
                        .comparing((TeacherDocumentBlockResponse block) -> !samePage(hit.pageNo(), block.pageNo()))
                        .thenComparingInt(block -> Math.abs(block.blockOrder() - hit.blockOrder()))

                        .thenComparingInt(TeacherDocumentBlockResponse::blockOrder))
                .flatMap(block -> parseImageAssetIds(block.imageRefs()).stream())
                .distinct()
                .limit(MAX_IMAGE_ASSETS_PER_HIT)
                .toList();
        if (nearbyAssetIds.isEmpty()) {
            return hit;
        }
        return new TeacherResourceBlockSearchResponse.Hit(
                hit.documentId(), hit.documentTitle(), hit.sourceType(), hit.permissionScope(), hit.blockId(),
                hit.blockType(), hit.blockOrder(), hit.chapter(), hit.section(), hit.pageNo(), hit.sourcePath(),
                hit.blockRole(), hit.graphTags(), hit.evidenceBlockIds(), hit.evidenceText(), hit.snippet(), hit.score(),
                nearbyAssetIds, List.of());
    }


    /** Null page numbers mean the source is unpaged and must fall back to block distance. */
    static boolean samePage(Integer hitPage, Integer imagePage) {
        return hitPage != null && hitPage.equals(imagePage);
    }


    /** Tests the exact leading question number without letting formula or solution-line numerals cross-bind figures. */
    static boolean matchesTopLevelQuestionNumber(String text, String expectedNumber) {
        Matcher matcher = TOP_LEVEL_QUESTION_NUMBER.matcher(textOrDefault(text, ""));
        return matcher.find() && expectedNumber.equals(matcher.group(1));
    }


    /** A subsequent numbered stem ends the source ownership window for the preceding question. */
    static boolean matchesAnyTopLevelQuestionNumber(String text) {
        return TOP_LEVEL_QUESTION_NUMBER.matcher(textOrDefault(text, "")).find();
    }


    /** Ensures only authenticated resource readers can use this backend-mediated search endpoint. */
    static void requireReaderRole(String viewerRole) {
        String normalizedRole = textOrDefault(viewerRole, "").toLowerCase(Locale.ROOT);
        if (!TeacherResourceVisibilityPolicy.isReaderRole(normalizedRole)) {
            throw new IllegalArgumentException("Teacher resource block search requires student, teacher, or admin role");
        }
    }


    static List<TeacherResourceDocumentResponse> filteredDocuments(
            List<TeacherResourceDocumentResponse> documents,
            TeacherResourceSearchFilter filter) {
        return documents.stream()
                // A registered browser URL is not searchable evidence. This gates every caller (teacher UI, MCP, and
                // handout workflow) on the same owner-scoped download, parser, and vector-index completion in MySQL.
                .filter(TeacherResourceReadiness::isReady)
                // Runtime fixtures and benchmark imports are never user teaching material.  Filtering them at the
                // shared search boundary protects the UI, MCP callers, ordinary Q&A, and handout generation alike.
                .filter(document -> !isSyntheticOrBenchmarkSource(document))
                .filter(document -> filter.documentIds().isEmpty() || filter.documentIds().contains(document.documentId()))
                .filter(document -> filter.permissionScopes().isEmpty()
                        || filter.permissionScopes().contains(textOrDefault(document.permissionScope(), "").toUpperCase(Locale.ROOT)))
                .filter(document -> TeacherResourceLibraryResolver.matchesAny(document, filter.sourceTypes()))
                .toList();
    }


    static boolean isSyntheticOrBenchmarkSource(TeacherResourceDocumentResponse document) {
        String text = normalizeText(String.join(" ",
                textOrDefault(document == null ? null : document.documentId(), ""),
                textOrDefault(document == null ? null : document.title(), ""),
                textOrDefault(document == null ? null : document.sourceIdentity(), ""),
                textOrDefault(document == null ? null : document.localPath(), ""),
                textOrDefault(document == null ? null : document.originalUrl(), "")));
        return text.contains("synthetic-natural-math-benchmark")
                || text.contains("benchmark-high-school-math")
                || text.contains("runtime-authored")
                || text.contains("runtime-teacher-resource")
                || text.contains("design-system-docs")
                || text.contains("knowledge-graph-spine")
                || text.contains("synthetic-natural")
                || text.contains("audit-feishu-rag")
                || text.matches(".*(?:^|[\\s/_-])runtime-[a-z0-9_-]+.*");
    }


    /**
     * If the caller narrowed the search space to textbook only, there is no value in running teacher-resource stage
     * one first. The real textbook retriever is already the canonical source for that library and produces a cleaner
     * candidate pool for the final merge.
     */
    static boolean isTextbookOnlyFilter(TeacherResourceSearchFilter filter) {
        if (filter == null) {
            return false;
        }
        if ((filter.permissionScopes() != null && !filter.permissionScopes().isEmpty())
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


    static boolean matchesTags(
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
     * Normalizes a query before graph alignment and semantic retrieval.
     */
    static String normalizeQuery(String query) {
        return normalizeText(textOrDefault(query, ""));
    }


    static List<String> queryClauses(String normalizedQuery) {
        LinkedHashSet<String> clauses = new LinkedHashSet<>();
        for (String value : QUERY_CLAUSE_SPLITTER.split(normalizedQuery)) {
            String clause = normalizeText(textOrDefault(value, ""));
            if (!clause.isBlank()) {
                clauses.add(clause);
            }
        }
        if (clauses.isEmpty()) {
            clauses.add(normalizedQuery);
        }
        return List.copyOf(clauses);
    }


    static List<String> normalizedQueryParts(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String tag = normalizeText(textOrDefault(value, ""));
            if (!tag.isBlank()) {

                normalized.add(tag);
            }
        }
        return List.copyOf(normalized);
    }


    /**
     * Keep one long non-routing clause even when graph alignment already matched another clause.
     *
     * <p>Teacher queries often start with a broad topic clause such as "数列方法怎么讲", then add the real
     * page- or block-discriminating evidence later in the sentence. If focus building keeps only the graph-matched
     * topic clause, stage-one document recall becomes generic again and sibling documents with the same module tag can
     * crowd out the actually correct handout. This helper preserves the normalized graph clue while still passing one
     * longest unseen user clause into semantic retrieval.</p>
     */
    static void appendLongestRemainingClauses(
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
                .forEach(clause -> addFocusedPart(focusedParts, clause));
    }


    static void addFocusedPart(LinkedHashSet<String> focusedParts, String candidate) {
        String normalized = normalizeText(textOrDefault(candidate, ""));
        if (normalized.isBlank()) {
            return;
        }
        for (String existing : focusedParts) {
            if (containsNormalized(existing, normalized) || containsNormalized(normalized, existing)) {
                return;
            }
        }
        focusedParts.add(normalized);
    }


    static boolean containsNormalized(String haystack, String needle) {
        String normalizedHaystack = normalizeText(textOrDefault(haystack, ""));
        String normalizedNeedle = normalizeText(textOrDefault(needle, ""));
        return !normalizedNeedle.isBlank() && normalizedHaystack.contains(normalizedNeedle);
    }


    /**
     * Teacher/agent callers often express the desired library inside the natural-language query but forget to pass the
     * dedicated {@code library} parameter. When that happens, running the full mixed teacher corpus first is both slow
     * and semantically wrong: the request already narrowed the evidence scope.
     *
     * <p>Only derive a library here when the structured filter did not already provide one, and only when the query
     * resolves to exactly one logical library family. This keeps the behavior generic and avoids overriding explicit
     * multi-library callers.</p>
     */
    static TeacherResourceSearchFilter normalizeFilter(
            TeacherResourceSearchFilter filter,
            String normalizedQuery) {
        TeacherResourceSearchFilter base = filter == null ? TeacherResourceSearchFilter.EMPTY : filter;
        if (base.sourceTypes() != null && !base.sourceTypes().isEmpty()) {
            return base;
        }
        List<String> derivedLibraries = queryLibrarySelectors(normalizedQuery);
        if (derivedLibraries.isEmpty()) {
            return base;
        }
        return TeacherResourceSearchFilter.of(
                base.permissionScopes(),
                base.documentIds(),
                derivedLibraries,
                base.tags());
    }


    /**
     * Parses only logical library selectors from the user query. This is intentionally limited to broad source-family
     * names such as textbook/Feishu/QQ bundle/gaokao/mock; it must never parse knowledge-point content here.
     */
    static List<String> queryLibrarySelectors(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> selectors = new LinkedHashSet<>();
        if (containsAny(normalizedQuery,
                "textbook",
                "教材库",
                "公共教材",

                "教材检索",
                "教材原文",
                "教材页",
                "课本原文",
                "课本",
                "教材")) {
            selectors.add("textbook");
        }
        if (containsAny(normalizedQuery,
                "feishu",
                "飞书")) {
            selectors.add("feishu");
        }
        if (containsAny(normalizedQuery,
                "qq_bundle",
                "qq专题",
                "qq 专题",
                "专题包",
                "qq")) {
            selectors.add("qq_bundle");
        }
        if (containsAny(normalizedQuery,
                "gaokao",
                "高考",
                "真题")) {
            selectors.add("gaokao");
        }
        if (containsAny(normalizedQuery,
                "mock_exam",
                "mock exam",
                "模拟题",
                "模拟卷",
                "模拟")) {
            selectors.add("mock_exam");
        }
        if (selectors.size() != 1) {
            return List.of();
        }
        return List.copyOf(selectors);
    }


    static void addSearchTerm(LinkedHashSet<String> terms, String candidate) {
        String normalizedCandidate = normalizeText(textOrDefault(candidate, ""));
        if (normalizedCandidate.isBlank()) {
            return;
        }
        if (QUERY_STOP_WORDS.contains(normalizedCandidate)) {
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
    static String normalizeText(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }


    /**
     * Real teacher folders can carry very long paths, neighboring block windows, and image references. Truncate only
     * the rerank view so the worker gets the strongest semantic clues without timing out; the response still returns
     * the original evidence text and snippets elsewhere.
     */
    static String truncateForRerank(String value, int maxChars) {
        String normalized = textOrDefault(value, "");
        if (normalized.isBlank() || maxChars <= 0 || normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars).strip() + "…";
    }


    static void appendRerankLine(StringBuilder builder, String label, String value) {
        String normalizedValue = textOrDefault(value, "");
        if (normalizedValue.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(label).append(": ").append(normalizedValue);
    }


    /**
     * Returns stripped text or a fallback when blank.
     */
    static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }


    /**
     * Returns stripped text or fails when backend identity is missing.
     */
    static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }


    static boolean containsAny(String haystack, String... needles) {
        String normalizedHaystack = normalizeText(textOrDefault(haystack, ""));
        for (String needle : needles) {
            if (!needle.isBlank() && normalizedHaystack.contains(normalizeText(needle))) {
                return true;
            }
        }
        return false;
    }


    static boolean isAsciiWordChar(char value) {
        return (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9')
                || value == '_'
                || value == '-';
    }


    static boolean isCjkChar(char value) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(value);
        return Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS.equals(block)
                || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A.equals(block)
                || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B.equals(block)
                || Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS.equals(block);
    }


    static List<String> parseStringArray(String json) {
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


    /**
     * imageRefs is a JSON object array because each asset carries page and source metadata. Keep this parser distinct
     * from graph-tag string arrays; treating it as a String list silently discarded asset ids after a successful hit.
     */
    static List<String> parseImageAssetIds(String json) {
        try {
            JsonNode values = OBJECT_MAPPER.readTree(textOrDefault(json, "[]"));
            if (!values.isArray()) {
                return List.of();
            }
            List<String> assetIds = new ArrayList<>();
            for (JsonNode value : values) {
                String assetId = value.path("assetId").asText("").strip();
                if (!assetId.isBlank()) {
                    assetIds.add(assetId);
                }
            }
            return List.copyOf(assetIds);
        } catch (Exception ignored) {
            return List.of();
        }
    }


    /** Formula JSON stays structured in storage, while reranking receives only bounded canonical evidence. */
    static String formulaEvidence(String formulaRefs) {
        try {
            JsonNode formulas = OBJECT_MAPPER.readTree(textOrDefault(formulaRefs, "[]"));
            if (!formulas.isArray()) {
                return "";
            }
            List<String> values = new ArrayList<>();
            for (JsonNode formula : formulas) {
                String plainText = formula.path("plainText").asText("").strip();
                String latex = formula.path("latex").asText("").strip();
                if (!plainText.isBlank()) {
                    values.add(plainText);
                }
                if (!latex.isBlank()) {
                    values.add(latex);
                }
            }
            return String.join(" ", values);
        } catch (Exception ignored) {
            return "";
        }
    }


    static String blockEvidenceText(TeacherDocumentBlockResponse block) {
        return normalizeText(String.join(
                " ",
                textOrDefault(block.rawText(), block.normalizedText()),
                formulaEvidence(block.formulaRefs())));
    }


    static String blockKey(String documentId, String blockId) {
        return documentId + ":" + blockId;
    }
}
