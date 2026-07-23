package com.doob.mathagent.teaching;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic first-pass merger for teaching draft sections.
 *
 * <p>This merger only applies backend-safe patch types. Unsupported patches stay visible in the merge result so later
 * reviewer agents or humans can continue from a structured checkpoint.</p>
 */
public final class TeachingDraftMerger {

    private static final Pattern STUDENT_ANSWER_LINE = Pattern.compile(
            "(?mi)^\\s*(?:答案|参考答案|评分点|得分|完整解析|教师版|讲评主线)\\s*[：:].*$");

    private TeachingDraftMerger() {
    }

    /**
     * Applies the subset of structured review patches that the backend can execute deterministically today.
     */
    public static TeachingDraftMergeResult merge(TeachingDraftSections sections, TeachingDraftReview review) {
        TeachingDraftSections safeSections = sections == null
                ? new TeachingDraftSections("", "", List.of(), List.of(), List.of(), List.of())
                : sections;
        TeachingDraftReview safeReview = review == null
                ? new TeachingDraftReview("READY", List.of(), List.of())
                : review;
        String mergedStudentWorksheet = safeSections.studentWorksheet();
        List<String> mergedLectureCards = safeSections.lectureCards();
        List<TeachingDraftMergeResult.AppliedPatch> appliedPatches = new ArrayList<>();
        List<TeachingDraftReview.ReviewPatch> rejectedPatches = new ArrayList<>();
        Set<String> resolvedFindingKeys = new LinkedHashSet<>();

        for (TeachingDraftReview.ReviewPatch patch : safeReview.patches()) {
            String findingKey = findingKey(patch.reviewerCode(), patch.targetSectionCode());
            if ("StudentLeakageReviewer".equals(patch.reviewerCode())
                    && "studentWorksheet".equals(patch.targetSectionCode())) {
                mergedStudentWorksheet = sanitizeStudentWorksheet(mergedStudentWorksheet);
                appliedPatches.add(new TeachingDraftMergeResult.AppliedPatch(
                        patch.reviewerCode(),
                        patch.targetSectionCode(),
                        patch.instruction()));
                resolvedFindingKeys.add(findingKey);
                continue;
            }
            if ("LectureCardReviewer".equals(patch.reviewerCode())
                    && "lectureCards".equals(patch.targetSectionCode())) {
                mergedLectureCards = normalizeLectureCards(mergedLectureCards);
                appliedPatches.add(new TeachingDraftMergeResult.AppliedPatch(
                        patch.reviewerCode(),
                        patch.targetSectionCode(),
                        patch.instruction()));
                resolvedFindingKeys.add(findingKey);
                continue;
            }
            rejectedPatches.add(patch);
        }

        TeachingDraftSections mergedSections = TeachingDraftSectionCollector.collect(
                safeSections.teacherExplanation(),
                mergedStudentWorksheet,
                mergedLectureCards,
                safeSections.exercises(),
                safeSections.sourceRefs(),
                safeSections.risks());
        List<TeachingDraftReview.ReviewFinding> remainingFindings = safeReview.findings().stream()
                .filter(finding -> !resolvedFindingKeys.contains(findingKey(finding.reviewerCode(), finding.sectionCode())))
                .toList();
        return new TeachingDraftMergeResult(
                mergeStatus(safeReview, appliedPatches, remainingFindings),
                mergedSections,
                appliedPatches,
                rejectedPatches,
                remainingFindings);
    }

    /**
     * Removes obvious teacher-only answer lines from the student worksheet while keeping the surrounding draft blocks.
     */
    private static String sanitizeStudentWorksheet(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        List<String> keptLines = new ArrayList<>();
        for (String rawLine : value.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine.strip();
            if (line.isBlank() || STUDENT_ANSWER_LINE.matcher(line).matches()) {
                continue;
            }
            keptLines.add(line);
        }
        return String.join("\n", keptLines).strip();
    }

    /**
     * Keeps each authored lecture card as one projector unit.
     *
     * <p>A card is already the output boundary of the lecture writer. Splitting it by punctuation here
     * destroys that boundary and makes the renderer turn one question into a long numbered list. The PDF
     * layer is responsible for giving every retained card its own page.</p>
     */
    private static List<String> normalizeLectureCards(List<String> lectureCards) {
        if (lectureCards == null || lectureCards.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String lectureCard : lectureCards) {
            if (lectureCard == null || lectureCard.isBlank()) {
                continue;
            }
            String body = stripLectureCardPrefix(lectureCard);
            String compact = compactLectureSegment(body);
            if (!compact.isBlank()) {
                normalized.add(compact);
            }
        }
        return List.copyOf(normalized);
    }

    private static String stripLectureCardPrefix(String lectureCard) {
        String trimmed = lectureCard == null ? "" : lectureCard.strip();
        int chineseColon = trimmed.indexOf('：');
        if (chineseColon > -1 && trimmed.substring(0, chineseColon + 1).contains("第")) {
            return trimmed.substring(chineseColon + 1).strip();
        }
        int asciiColon = trimmed.indexOf(':');
        if (asciiColon > -1 && trimmed.substring(0, asciiColon + 1).contains("第")) {
            return trimmed.substring(asciiColon + 1).strip();
        }
        return trimmed;
    }

    private static String compactLectureSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            return "";
        }
        String compact = segment
                .replaceAll("^[-•*\\s]+", "")
                .replaceAll("\\s+", " ")
                .strip();
        if (compact.endsWith("。") || compact.endsWith("；")) {
            compact = compact.substring(0, compact.length() - 1).strip();
        }
        return compact;
    }

    private static String mergeStatus(
            TeachingDraftReview review,
            List<TeachingDraftMergeResult.AppliedPatch> appliedPatches,
            List<TeachingDraftReview.ReviewFinding> remainingFindings) {
        if ((review == null || review.findings().isEmpty()) && appliedPatches.isEmpty()) {
            return "READY";
        }
        return remainingFindings.isEmpty() ? "MERGED" : "NEEDS_ATTENTION";
    }

    private static String findingKey(String reviewerCode, String sectionCode) {
        return (reviewerCode == null ? "" : reviewerCode) + "::" + (sectionCode == null ? "" : sectionCode);
    }
}
