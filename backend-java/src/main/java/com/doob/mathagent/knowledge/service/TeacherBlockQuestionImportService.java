package com.doob.mathagent.knowledge.service;

import com.doob.mathagent.knowledge.vo.KnowledgePointResponse;
import com.doob.mathagent.knowledge.vo.QuestionBankItemResponse;
import com.doob.mathagent.knowledge.vo.TeacherBlockQuestionImportResponse;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockStore;
import com.doob.mathagent.teacher.document.TeacherResourceStore;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.document.TeacherResourceReadiness;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Imports real parsed teacher resource blocks into the standard question bank.
 */
@Service
public class TeacherBlockQuestionImportService {

    /** Resource-library scope used by a folder shared with every account in the current tenant. */
    private static final String TENANT_PUBLIC_SCOPE = "TENANT_PUBLIC";
    /** Standard question-bank scope that is visible to students, teachers, and administrators. */
    private static final String SHARED_QUESTION_SCOPE = "MATH_VIP";
    private static final int TITLE_LIMIT = 80;
    /** A printable exercise stem longer than this is a lesson/article block, not one atomic question. */
    private static final int MAX_PRINTABLE_QUESTION_CHARS = 4000;
    /** A parsed block needs an actual prompt signal, not merely a chapter title containing the character “题”. */
    private static final Pattern QUESTION_PROMPT_SIGNAL = Pattern.compile(
            "(?:求(?:证|出|得|解|值|下列|该)?|证明|计算|判断|填写|作答|选择|问[：:]|则|多少(?:种|个|条|种方法)?|共有|何值|几种|哪些|是否|能否|有几|请)[^。；]{1,}");
    /** Separates a visible source answer/analysis section from the question stem without treating inline prose as a marker. */
    private static final Pattern SOURCE_ANSWER_MARKER = Pattern.compile(
            "(?m)^\\h*(?:[-—]{3,}\\s*)?(?:#{1,6}\\h*)?(?:【\\h*)?(?:答案|参考答案|答案要点|解析|解答|解)(?:\\h*】)?\\h*[：:]?");
    /** Narrative teaching notes are searchable source material, but not exercises even when they mention a method. */
    private static final Pattern INSTRUCTIONAL_ARTICLE_SIGNAL = Pattern.compile(
            "(?:一定要记住|考试时候|自己算就行|多默念|复习一下|下面的公式|直接默写|装模作样|"
                    + "涂色问题的核心|总结，|我们要做的就只有|通过上面的处理我们发现|"
                    + "每种颜色情况都需要|给最小组合分配颜色)");
    /** A rendered exam page can include its next-page analysis; it is evidence, never a new printable problem. */
    private static final Pattern SOURCE_EXPLANATION_LEAD = Pattern.compile(
            "^(?:【?\s*(?:解析|分析|详解|解答)\s*】?|(?:方法|解法)\s*[一二三四五]\s*[：:])");
    /** A sub-question or method sentence is not printable unless its own block retained real problem context. */
    private static final Pattern COMPLETE_STEM_CONTEXT = Pattern.compile(
            "(?:如图|已知|若[\\s，,]|设[\\s，,]|在[^。；]{2,80}中|题目[：:]|选择题|填空题|"
                    + "(?:例题|变式|证明)[：:]|\\d{4}年[^。；]{2,}|^(?:求|证明))");
    /** Page-level exam splitting already proved a real top-level question number; do not reject compact stems such as “若集合…”. */
    private static final Pattern NUMBERED_EXAM_STEM = Pattern.compile(
            "^\\s*[1-9]\\d?(?:[.．、]|\\h{2,})(?=\\S)");
    /** An OCR square/replacement glyph can conceal perpendicular, parallel, or inclusion relations. */
    private static final Pattern UNRESOLVED_MATHEMATICAL_OCR_GLYPH = Pattern.compile("[□�]");
    /**
     * Starts of independent numbered questions in a text-extracted exam page. Two spaces distinguish `1  已知…`
     * from ordinary inline numerals while the punctuation branch accepts common PDF extraction forms such as `2.`.
     * The first digit is deliberately non-zero: analysis pages commonly begin with decimal evidence such as `0.038`,
     * and treating that decimal as question zero creates a false extra bank item.
     */
    private static final Pattern TOP_LEVEL_NUMBERED_QUESTION = Pattern.compile(
            "(?m)^\\h*[1-9]\\d?(?:[.．、]\\h*|\\h{2,})(?=\\S)(?!(?:由|因为|所以|故|因此|当|令|解|可得|得到|从而|于是|不妨|显然|代入|联立|利用|根据|这个|这里|此时|可知))");
    /** Exam instructions contain imperative verbs but are never mathematical questions. */
    private static final Pattern EXAM_ADMINISTRATION_NOTICE = Pattern.compile(
            "(?:注意事项|答题卡|准考证|考试结束|选择题的作答|填空题和解答题的作答)");
    /** A page can end one question immediately before the next exam section heading; retain only the atomic stem. */
    private static final Pattern FOLLOWING_EXAM_SECTION = Pattern.compile(
            "(?s)\\n\\s*(?:一|二|三|四|五)、(?:单项选择题|多项选择题|填空题|解答题).*$");
    /**
     * A paged source becomes authoritative only after it can independently supply a usable handout-sized set.
     * This avoids silently discarding normal Feishu/Markdown resources, while preventing a DOCX's low-fidelity
     * paragraph fragments from competing with its already verified page transcription.
     */
    private static final int MIN_PAGE_BACKED_ATOMIC_QUESTION_COUNT = 10;
    /** Page transcription below this confidence is retained for RAG inspection but never promoted to a printable bank row. */
    private static final double MIN_PAGE_BACKED_BLOCK_CONFIDENCE = 0.90d;

    private final TeacherResourceStore resourceStore;
    private final TeacherDocumentBlockStore blockStore;
    private final KnowledgeQuestionBankService questionBankService;
    private final KnowledgeQuestionBankStore questionBankStore;

    /**
     * Creates the import service.
     *
     * @param resourceStore teacher resource document store
     * @param blockStore parsed block store
     * @param questionBankService question bank write service
     * @param questionBankStore question bank de-duplication store
     */
    public TeacherBlockQuestionImportService(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore,
            KnowledgeQuestionBankService questionBankService,
            KnowledgeQuestionBankStore questionBankStore) {
        this.resourceStore = resourceStore;
        this.blockStore = blockStore;
        this.questionBankService = questionBankService;
        this.questionBankStore = questionBankStore;
    }

    /**
     * Imports visible parsed blocks from one teacher resource document.
     */
    public TeacherBlockQuestionImportResponse importFromTeacherResource(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String documentId) {
        String normalizedTenantId = requireText(tenantId, "tenantId");
        String normalizedRole = requireText(viewerRole, "viewerRole").toLowerCase(Locale.ROOT);
        String normalizedSubjectId = requireText(viewerSubjectId, "viewerSubjectId");
        String normalizedDocumentId = requireText(documentId, "documentId");
        requireTeacherOrAdmin(normalizedRole);
        TeacherResourceDocumentResponse document = resourceStore.find(normalizedTenantId, normalizedDocumentId);
        if (document == null || !visibleForImport(document, normalizedRole, normalizedSubjectId)) {
            throw new IllegalArgumentException("Teacher resource document is not visible for import");
        }
        if (!TeacherResourceReadiness.isReady(document)) {
            throw new IllegalStateException("Teacher resource document is not ready for import");
        }
        List<TeacherDocumentBlockResponse> blocks = blockStore.listByDocument(normalizedTenantId, normalizedDocumentId);
        /*
         * The parser and the question bank form one incremental pipeline. Archive imported question rows whose
         * source block/checksum disappeared from the latest active parse set before inserting new rows; otherwise
         * checksum changes create stale duplicates that survive forever and poison downstream retrieval.
         */
        List<TeacherDocumentBlockResponse> importBlocks = preferredImportBlocks(blocks);
        ImportCandidateSet candidateSet = collectCandidates(importBlocks);
        questionBankStore.archiveQuestionsBySourceDocumentExcept(
                normalizedTenantId,
                document.documentId(),
                activeQuestionSourceKeys(candidateSet.candidates()));
        List<QuestionBankItemResponse> imported = new ArrayList<>();
        Set<String> linkedKnowledgePointIds = new LinkedHashSet<>();
        int duplicates = candidateSet.duplicateCount();
        // Teacher resources and the question bank use different names for tenant-wide visibility. Normalize once so
        // both the generated point and its question retain the same permission boundary during every re-import.
        String effectiveQuestionScope = questionBankScope(document.permissionScope());
        for (ImportCandidate candidate : candidateSet.candidates()) {
            TeacherDocumentBlockResponse block = candidate.block();
            AtomicSourcePart sourcePart = candidate.sourcePart();
            ImportedQuestion importedQuestion = candidate.question();
            var existingQuestion = questionBankStore.findQuestionBySource(
                    normalizedTenantId,
                    document.documentId(),
                    sourcePart.sourceBlockId(),
                    sourcePart.sourceChecksum());
                String questionTitle = knowledgePointName(block, document.title()) + " / " + title(importedQuestion.questionText());
                String sourceFileName = sourceFileName(block.sourcePath());
                if (!sourceFileName.isBlank()) {
                    questionTitle = sourceFileName + " / " + questionTitle;
                }
                if (existingQuestion.isPresent()) {
                    if (equivalentImport(
                            existingQuestion.get(), importedQuestion, questionTitle, effectiveQuestionScope)) {
                        duplicates++;
                        continue;
                    }
                    // Child identifiers include the parent checksum, so a source re-sync replaces only the affected
                    // atomic question and never leaves the earlier broad-page row available to lesson retrieval.
                    questionBankStore.archiveQuestion(normalizedTenantId, existingQuestion.get().questionId());
                }
                KnowledgePointResponse point = questionBankService.ensureKnowledgePoint(
                        normalizedTenantId,
                        normalizedRole,
                        normalizedSubjectId,
                        effectiveQuestionScope,
                        knowledgePointName(block, document.title()),
                        chapterPath(block),
                        "teacher_resource_import:" + document.documentId());
                QuestionBankItemResponse question = questionBankService.createImportedQuestion(
                        normalizedTenantId,
                        normalizedRole,
                        normalizedSubjectId,
                        effectiveQuestionScope,
                        questionTitle,
                        importedQuestion.questionText(),
                        importedQuestion.answerJson(),
                        "medium",
                        document.documentId(),
                        sourcePart.sourceBlockId(),
                        sourcePart.sourceChecksum(),
                        List.of(point.knowledgePointId()));
                imported.add(question);
                linkedKnowledgePointIds.add(point.knowledgePointId());
        }
        return new TeacherBlockQuestionImportResponse(
                document.documentId(),
                blocks.size(),
                imported.size(),
                candidateSet.skippedCount(),
                duplicates,
                linkedKnowledgePointIds.size(),
                List.copyOf(imported));
    }

    /**
     * Searches imported and manually created questions through the normal question-bank read path.
     */
    public List<QuestionBankItemResponse> searchQuestions(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String query,
            int limit) {
        return questionBankService.searchQuestions(tenantId, viewerRole, viewerSubjectId, query, limit);
    }

    /**
     * Uses backend role, owner and original source scope to decide import visibility.
     */
    private static boolean visibleForImport(
            TeacherResourceDocumentResponse document,
            String viewerRole,
            String viewerSubjectId) {
        if ("admin".equals(viewerRole)) {
            return true;
        }
        if (!"teacher".equals(viewerRole)) {
            return false;
        }
        return viewerSubjectId.equals(document.ownerSubjectId()) || isSharedScope(document.permissionScope());
    }

    /**
     * Allows import from shared teacher resources that are already exposed by the backend.
     */
    private static boolean isSharedScope(String permissionScope) {
        return SHARED_QUESTION_SCOPE.equals(permissionScope)
                || "PUBLIC_TEXTBOOK".equals(permissionScope)
                || TENANT_PUBLIC_SCOPE.equals(permissionScope)
                || "CLASS_AUTHORIZED".equals(permissionScope);
    }

    /** Maps resource-library tenant sharing onto the shared scope supported by question visibility filters. */
    private static String questionBankScope(String resourceScope) {
        return TENANT_PUBLIC_SCOPE.equals(textOrDefault(resourceScope, "").toUpperCase(Locale.ROOT))
                ? SHARED_QUESTION_SCOPE
                : resourceScope;
    }

    /**
     * Splits one parsed source block into an atomic printable stem and separately stored source-verified answer.
     * Heading and instructional blocks are intentionally rejected even when their text contains “题” or “例”.
     */
    private static ImportedQuestion parseAtomicQuestion(String text) {
        String normalized = FOLLOWING_EXAM_SECTION.matcher(
                        textOrDefault(text, "").replace("\r\n", "\n").replace('\r', '\n')
                                // This private-use glyph is the verified PDF text-layer encoding of a greater-than
                                // sign. Normalize it before the corruption guard: rejecting the entire cross-page
                                // question would discard a real, otherwise high-confidence source stem.
                                .replace('\uF03E', '>')
                                .replace('\uF03C', '<'))
                .replaceFirst("")
                .strip();
        if (normalized.isBlank()) {
            return null;
        }
        if (EXAM_ADMINISTRATION_NOTICE.matcher(normalized).find() || numberedQuestionCount(normalized) > 1) {
            // Do not pretend a cover page, instruction paragraph or still-unsplit page is one question. It remains
            // searchable teacher source material, but it cannot increase the qualified-handout question count.
            return null;
        }
        Matcher answerMarker = SOURCE_ANSWER_MARKER.matcher(normalized);
        boolean hasAnswerMarker = answerMarker.find();
        String questionText = hasAnswerMarker ? normalized.substring(0, answerMarker.start()).strip() : normalized;
        if (!QUESTION_PROMPT_SIGNAL.matcher(questionText.replaceAll("\\s+", " ")).find()) {
            return null;
        }
        String answerText = hasAnswerMarker ? normalized.substring(answerMarker.end()).strip() : "";
        if (!hasAnswerMarker) {
            int promptEnd = completedPromptEnd(questionText);
            if (promptEnd > 0 && promptEnd < questionText.length()) {
                answerText = questionText.substring(promptEnd).strip();
                questionText = questionText.substring(0, promptEnd).strip();
            }
        }
        // A broad teacher explanation can mention “求/什么/定理” many times.  It is useful RAG evidence but must
        // never be mislabeled as a question, otherwise one article inflates the ten-question readiness gate.
        if (questionText.isBlank() || questionText.length() > MAX_PRINTABLE_QUESTION_CHARS
                // Reject before persistence: deleting the character would turn an unknown geometric relation into a
                // plausible but mathematically different prompt, while keeping it makes an unreviewable handout.
                || UNRESOLVED_MATHEMATICAL_OCR_GLYPH.matcher(questionText).find()
                || INSTRUCTIONAL_ARTICLE_SIGNAL.matcher(questionText).find()
                || SOURCE_EXPLANATION_LEAD.matcher(questionText).find()
                || (!NUMBERED_EXAM_STEM.matcher(questionText).find()
                        && !COMPLETE_STEM_CONTEXT.matcher(questionText).find())) {
            return null;
        }
        return new ImportedQuestion(questionText, answerJson(answerText));
    }

    /**
     * Extracts a finished source prompt before adjacent teacher narration when the source omitted a dedicated answer
     * heading.  The prompt signal must occur before its Chinese/ASCII sentence terminator, so an explanatory sentence
     * containing “首先” is never silently absorbed into the printable question.
     */
    private static int completedPromptEnd(String text) {
        Matcher prompt = QUESTION_PROMPT_SIGNAL.matcher(text);
        if (!prompt.find()) {
            return -1;
        }
        for (int index = prompt.end(); index < text.length(); index += 1) {
            char character = text.charAt(index);
            if (character == '。' || character == '？' || character == '?') {
                return index + 1;
            }
        }
        return -1;
    }

    /** Creates valid compact JSON so answer formatting stays structured across retrieval and PDF rendering. */
    private static String answerJson(String answerText) {
        String compact = textOrDefault(answerText, "").replaceAll("\\s+", " ").strip();
        if (compact.isBlank()) {
            return "{}";
        }
        String escaped = compact.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"answer\":\"" + escaped + "\"}";
    }

    /** Distinguishes a genuine unchanged import from a same-source row created by the old broad-block parser. */
    private static boolean equivalentImport(
            QuestionBankItemRecord existing,
            ImportedQuestion candidate,
            String questionTitle,
            String permissionScope) {
        return existing.questionText().equals(candidate.questionText())
                && existing.answerJson().equals(candidate.answerJson())
                && existing.questionTitle().equals(questionTitle)
                && existing.permissionScope().equals(permissionScope);
    }

    /**
     * Chooses section before chapter to avoid over-broad imported knowledge points.
     */
    private static String knowledgePointName(TeacherDocumentBlockResponse block) {
        String section = textOrDefault(block.section(), "");
        if (!section.isBlank()) {
            return section;
        }
        return textOrDefault(block.chapter(), "\u672a\u5f52\u7c7b\u77e5\u8bc6\u70b9");
    }

    /**
     * Keeps titled variations in the same retrieval group as their source document.  Blocks labelled “例题/变式”
     * describe a child row, not a new curriculum point; using the document title here makes all real variants
     * discoverable by one lesson query while preserving the child wording in the question title.
     */
    private static String knowledgePointName(TeacherDocumentBlockResponse block, String documentTitle) {
        String section = textOrDefault(block.section(), "");
        if (!section.isBlank() && !section.matches("^(?:例题|变式|练习)\\s*[:：].*$")) {
            return section;
        }
        String sourceTitle = textOrDefault(documentTitle, "");
        return sourceTitle.isBlank() ? knowledgePointName(block) : sourceTitle;
    }

    /**
     * Builds a stable chapter path from parsed document headings.
     */
    private static String chapterPath(TeacherDocumentBlockResponse block) {
        String chapter = textOrDefault(block.chapter(), "");
        String section = textOrDefault(block.section(), "");
        if (chapter.isBlank()) {
            return section;
        }
        if (section.isBlank()) {
            return chapter;
        }
        return chapter + "/" + section;
    }

    /**
     * Builds a compact question title from the real source text.
     */
    private static String title(String questionText) {
        String compact = textOrDefault(questionText, "").replaceAll("\\s+", " ");
        if (compact.length() <= TITLE_LIMIT) {
            return compact;
        }
        return compact.substring(0, TITLE_LIMIT);
    }

    /**
     * Ensures only teacher/admin backend subjects can import teacher resources.
     */
    private static void requireTeacherOrAdmin(String viewerRole) {
        if (!"teacher".equals(viewerRole) && !"admin".equals(viewerRole)) {
            throw new IllegalArgumentException("Teacher block question import requires teacher or admin role");
        }
    }

    /**
     * Requires non-blank text.
     */
    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.strip();
    }

    /**
     * Returns stripped text or a fallback when blank.
     */
    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    private static Set<String> activeQuestionSourceKeys(List<ImportCandidate> candidates) {
        Set<String> keys = new LinkedHashSet<>();
        for (ImportCandidate candidate : candidates) {
            AtomicSourcePart part = candidate.sourcePart();
            keys.add(sourceKey(part.sourceBlockId(), part.sourceChecksum()));
        }
        return keys;
    }

    /**
     * Parses before writing so a blank paper and its solution paper can be paired deterministically.
     * The solution candidate wins when it contains source answer evidence; the PDF filename remains in the row title.
     */
    private static ImportCandidateSet collectCandidates(List<TeacherDocumentBlockResponse> blocks) {
        List<ImportCandidate> parsed = new ArrayList<>();
        int skipped = 0;
        for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex += 1) {
            TeacherDocumentBlockResponse block = blocks.get(blockIndex);
            String prefix = pageContinuationPrefix(blocks, blockIndex);
            for (AtomicSourcePart sourcePart : splitAtomicSourceParts(block, prefix)) {
                ImportedQuestion question = parseAtomicQuestion(sourcePart.text());
                if (question != null && isGaokaoExamPdf(block.sourcePath())
                        && topLevelQuestionNumber(question.questionText()).isBlank()) {
                    // A rendered exam page without a top-level number is normally an instruction/solution residue.
                    // Keeping it would create pseudo-questions such as a lone statistical value or answer paragraph.
                    question = null;
                }
                if (question != null && "{}".equals(question.answerJson())) {
                    String continuationAnswer = followingPageAnswerEvidence(blocks, blockIndex, question.questionText());
                    if (!continuationAnswer.isBlank()) {
                        question = new ImportedQuestion(question.questionText(), answerJson(continuationAnswer));
                    }
                }
                if (question == null) {
                    skipped++;
                } else {
                    parsed.add(new ImportCandidate(block, sourcePart, question));
                }
            }
        }

        Map<String, ImportCandidate> canonical = new LinkedHashMap<>();
        int duplicateCount = 0;
        for (ImportCandidate candidate : parsed) {
            String key = pairedExamQuestionKey(candidate);
            ImportCandidate previous = canonical.get(key);
            if (previous == null) {
                canonical.put(key, candidate);
                continue;
            }
            // Only blank/solution variants share a key; unrelated PDFs retain separate source-traceable rows.
            if (hasAnswer(candidate.question()) && !hasAnswer(previous.question())) {
                canonical.put(key, candidate);
            }
            duplicateCount++;
        }
        return new ImportCandidateSet(List.copyOf(canonical.values()), skipped, duplicateCount);
    }

    /** Uses the PDF basename and top-level number so only blank/solution variants of one paper collapse together. */
    private static String pairedExamQuestionKey(ImportCandidate candidate) {
        String file = sourceFileName(candidate.block().sourcePath()).toLowerCase(Locale.ROOT)
                .replace("（空白卷）", "").replace("（解析卷）", "")
                .replace("(空白卷)", "").replace("(解析卷)", "");
        String number = topLevelQuestionNumber(candidate.question().questionText());
        if (file.isBlank() || number.isBlank()) {
            return "source:" + normalizeQuestionText(candidate.question().questionText());
        }
        return "pdf:" + file + "#q" + number;
    }

    private static boolean hasAnswer(ImportedQuestion question) {
        return question != null && question.answerJson() != null && !"{}".equals(question.answerJson());
    }

    private static String topLevelQuestionNumber(String text) {
        Matcher matcher = Pattern.compile("^\\s*(\\d{1,2})(?:[.．、]|\\s{2,})").matcher(textOrDefault(text, ""));
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String normalizeQuestionText(String text) {
        return textOrDefault(text, "").replaceAll("\\s+", " ").strip();
    }

    private static String sourceFileName(String sourcePath) {
        String normalized = textOrDefault(sourcePath, "").replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    /** Limits the numbered-page rule to high-school exam PDFs; ordinary teacher PDFs may contain unnumbered examples. */
    private static boolean isGaokaoExamPdf(String sourcePath) {
        String file = sourceFileName(sourcePath).toLowerCase(Locale.ROOT);
        return file.endsWith(".pdf") && (file.contains("高考") || file.contains("真题") || file.contains("试卷"));
    }

    /**
     * Chooses the least lossy representation of one source without mixing two parser generations.
     *
     * <p>A large office document often contains both paragraph-level extraction and page-level multimodal
     * transcription. The former may drop an equation but still look like a short Chinese sentence (for example
     * {@code 若 ，则 ，}), which is dangerous because it passes superficial prompt checks. Once ten or more
     * independently printable page children exist, all imports for that document must therefore come from those
     * page blocks alone. Smaller/non-paged resources keep the conservative legacy block route.</p>
     */
    private static List<TeacherDocumentBlockResponse> preferredImportBlocks(List<TeacherDocumentBlockResponse> blocks) {
        List<TeacherDocumentBlockResponse> pageBlocks = blocks.stream()
                .filter(block -> block.pageNo() != null)
                .filter(block -> block.confidence() >= MIN_PAGE_BACKED_BLOCK_CONFIDENCE)
                // Storage writes from parallel page transcription are not guaranteed to preserve document order.
                // Continuation repair may only consult the physically adjacent source page, never a random block.
                .sorted(java.util.Comparator
                        .comparing((TeacherDocumentBlockResponse block) -> textOrDefault(block.sourcePath(), ""))
                        .thenComparingInt(TeacherDocumentBlockResponse::pageNo))
                .toList();
        long pageQuestionCount = pageBlocks.stream()
                .flatMap(block -> splitAtomicSourceParts(block).stream())
                .filter(part -> parseAtomicQuestion(part.text()) != null)
                .count();
        return pageQuestionCount >= MIN_PAGE_BACKED_ATOMIC_QUESTION_COUNT ? pageBlocks : blocks;
    }

    /**
     * Splits text-extracted exam pages before parsing. Each child keeps the original page block as an immutable prefix
     * and adds only a stable ordinal, so the source page, checksum and permission boundary remain auditable.
     */
    private static List<AtomicSourcePart> splitAtomicSourceParts(TeacherDocumentBlockResponse block) {
        return splitAtomicSourceParts(block, "");
    }

    /**
     * Restores a question whose numbered opening is at the foot of one high-confidence page and whose visible asks
     * continue at the top of the next page.  The merged child retains the continuation page's stable identity, while
     * the inherited prefix comes only from the immediately preceding page in the same document.
     */
    private static List<AtomicSourcePart> splitAtomicSourceParts(
            TeacherDocumentBlockResponse block, String continuationPrefix) {
        String text = textOrDefault(block.rawText(), block.normalizedText()).replace("\r\n", "\n").replace('\r', '\n');
        if (continuationPrefix != null && !continuationPrefix.isBlank()
                && numberedQuestionCount(text) == 0) {
            text = continuationPrefix.strip() + "\n" + text;
        }
        Matcher matcher = TOP_LEVEL_NUMBERED_QUESTION.matcher(text);
        List<Integer> starts = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
        }
        if (starts.isEmpty()) {
            return List.of(new AtomicSourcePart(block.blockId(), textOrDefault(block.checksum(), ""), text));
        }
        List<AtomicSourcePart> parts = new ArrayList<>();
        for (int index = 0; index < starts.size(); index += 1) {
            int end = index + 1 < starts.size() ? starts.get(index + 1) : text.length();
            String question = text.substring(starts.get(index), end).strip();
            String childId = textOrDefault(block.blockId(), "block") + "#q" + (index + 1);
            String childChecksum = childChecksum(block.checksum(), question);
            parts.add(new AtomicSourcePart(childId, childChecksum, question));
        }
        return List.copyOf(parts);
    }

    /** Finds the last numbered stem on the immediately preceding page for a page-top continuation only. */
    private static String pageContinuationPrefix(List<TeacherDocumentBlockResponse> blocks, int index) {
        if (blocks == null || index <= 0 || index >= blocks.size()) {
            return "";
        }
        TeacherDocumentBlockResponse current = blocks.get(index);
        TeacherDocumentBlockResponse previous = blocks.get(index - 1);
        if (current.pageNo() == null || previous.pageNo() == null
                || current.pageNo().intValue() != previous.pageNo().intValue() + 1
                || !sameSourcePath(current, previous)) {
            return "";
        }
        String currentText = textOrDefault(current.rawText(), current.normalizedText()).strip();
        if (currentText.isBlank() || numberedQuestionCount(currentText) > 0) {
            return "";
        }
        String previousText = textOrDefault(previous.rawText(), previous.normalizedText())
                .replace("\r\n", "\n").replace('\r', '\n');
        Matcher matcher = TOP_LEVEL_NUMBERED_QUESTION.matcher(previousText);
        int lastStart = -1;
        while (matcher.find()) {
            lastStart = matcher.start();
        }
        return lastStart < 0 ? "" : previousText.substring(lastStart).strip();
    }

    /** Returns only an adjacent official solution page for a multi-part source question, never a loose RAG hit. */
    private static String followingPageAnswerEvidence(
            List<TeacherDocumentBlockResponse> blocks, int index, String questionText) {
        if (blocks == null || index < 0 || index + 1 >= blocks.size() || questionText == null
                || !(questionText.contains("（1）") || questionText.contains("(1)"))) {
            return "";
        }
        TeacherDocumentBlockResponse current = blocks.get(index);
        TeacherDocumentBlockResponse next = blocks.get(index + 1);
        if (current.pageNo() == null || next.pageNo() == null
                || next.pageNo().intValue() != current.pageNo().intValue() + 1
                || !sameSourcePath(current, next)) {
            return "";
        }
        String raw = textOrDefault(next.rawText(), next.normalizedText()).strip();
        // Require an explicit source-solution signal. A following question page is not an answer page.
        return raw.contains("【小问") || raw.contains("【详解】") || raw.contains("展开即得")
                ? raw
                : "";
    }

    /** Counts the same top-level starts used by the child splitter to reject any residual broad page safely. */
    private static int numberedQuestionCount(String text) {
        Matcher matcher = TOP_LEVEL_NUMBERED_QUESTION.matcher(textOrDefault(text, ""));
        int count = 0;
        while (matcher.find()) {
            count += 1;
        }
        return count;
    }

    /** Keeps child checksums within the persistent SHA-256 column while tying each child to the parent source revision. */
    private static String childChecksum(String parentChecksum, String questionText) {
        try {
            byte[] bytes = (textOrDefault(parentChecksum, "") + "\n" + textOrDefault(questionText, ""))
                    .getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for source-traceable question import", exception);
        }
    }

    private static String sourceKey(String sourceBlockId, String sourceChecksum) {
        return textOrDefault(sourceBlockId, "") + "\n" + textOrDefault(sourceChecksum, "");
    }

    /** Prevents page-continuation repair from borrowing text or answers from a different PDF in one folder upload. */
    private static boolean sameSourcePath(TeacherDocumentBlockResponse left, TeacherDocumentBlockResponse right) {
        return textOrDefault(left == null ? null : left.sourcePath(), "")
                .equals(textOrDefault(right == null ? null : right.sourcePath(), ""));
    }

    /** Immutable, source-traceable result of conservative block parsing before it reaches the shared question bank. */
    private record ImportedQuestion(String questionText, String answerJson) {
    }

    /** Candidate retained between parsing and persistence so paired PDF variants can be resolved once. */
    private record ImportCandidate(
            TeacherDocumentBlockResponse block,
            AtomicSourcePart sourcePart,
            ImportedQuestion question) {
    }

    /** Immutable import summary after non-question blocks and blank/solution duplicates are accounted for. */
    private record ImportCandidateSet(
            List<ImportCandidate> candidates,
            int skippedCount,
            int duplicateCount) {
    }

    /** A child of one parsed source page with an independently durable question-bank identity. */
    private record AtomicSourcePart(String sourceBlockId, String sourceChecksum, String text) {
    }
}
