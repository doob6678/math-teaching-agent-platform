package com.doob.mathagent.teacher.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService.BlockContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TeacherResourceBlockSearchPolicyTest {

    private static final TeacherResourceDocumentResponse DOCUMENT = new TeacherResourceDocumentResponse(
            "file-1",
            "tenant-1",
            "teacher-1",
            "feishu",
            "解析几何讲义",
            "",
            "",
            "TEACHER_SHARED",
            "synced",
            "parsed",
            "ready",
            "ready",
            List.of());

    @Test
    void querySpecificBodySupportCanBeatSmallVectorCosineGap() {
        BlockContext generic = context(block(
                "generic",
                4,
                "椭圆基础",
                "圆的特殊情形",
                "当离心率 e=0 时，椭圆退化为圆。",
                "[\"椭圆\"]"));
        BlockContext specific = context(block(
                "specific",
                3,
                "椭圆基础",
                "离心率与形状",
                "离心率 e 越大，短半轴相对越短，椭圆越扁。",
                "[\"椭圆\"]"));
        Map<String, Double> vectors = Map.of(
                "file-1:generic", 0.80d,
                "file-1:specific", 0.75d);

        BlockContext selected = List.of(generic, specific).stream()
                .sorted(TeacherResourceBlockSearchPolicy.representativeBlockComparator(
                        DOCUMENT,
                        vectors,
                        Map.of(),
                        "椭圆的离心率变大时，短半轴和图形扁平程度怎样变化？",
                        new String[]{"离心率", "短半轴", "扁平"}))
                .findFirst()
                .orElseThrow();

        assertThat(selected.block().blockId()).isEqualTo("specific");
    }

    @Test
    void strongVectorSignalCannotBeOverriddenByBoundedLexicalSupport() {
        BlockContext vectorStrong = context(block(
                "vector-strong",
                4,
                "椭圆基础",
                "",
                "离心率的定义和性质。",
                "[]"));
        BlockContext lexicalStrong = context(block(
                "lexical-strong",
                3,
                "椭圆基础",
                "离心率与形状",
                "离心率变大时短半轴变短，图形变扁。",
                "[]"));
        Map<String, Double> vectors = Map.of(
                "file-1:vector-strong", 0.95d,
                "file-1:lexical-strong", 0.70d);

        BlockContext selected = List.of(vectorStrong, lexicalStrong).stream()
                .sorted(TeacherResourceBlockSearchPolicy.representativeBlockComparator(
                        DOCUMENT,
                        vectors,
                        Map.of(),
                        "离心率变大时短半轴怎样变化",
                        new String[]{"离心率", "短半轴"}))
                .findFirst()
                .orElseThrow();

        assertThat(selected.block().blockId()).isEqualTo("vector-strong");
    }

    @Test
    void graphTagAlignmentParticipatesBeforeBlockOrder() {
        BlockContext unaligned = context(block(
                "unaligned",
                1,
                "空间几何",
                "线面关系",
                "空间几何中的基本性质。",
                "[\"直线\"]"));
        BlockContext aligned = context(block(
                "aligned",
                4,
                "空间几何",
                "线面关系",
                "空间几何中的基本性质。",
                "[\"线面平行判定\"]"));

        BlockContext selected = List.of(unaligned, aligned).stream()
                .sorted(TeacherResourceBlockSearchPolicy.representativeBlockComparator(
                        DOCUMENT,
                        Map.of("file-1:unaligned", 0.70d, "file-1:aligned", 0.70d),
                        Map.of(),
                        "线面平行判定条件",
                        new String[]{"线面平行", "判定"}))
                .findFirst()
                .orElseThrow();

        assertThat(selected.block().blockId()).isEqualTo("aligned");
    }

    @Test
    void blockOrderIsOnlyTheFinalTieBreaker() {
        BlockContext later = context(block(
                "later",
                4,
                "同一章节",
                "同一小节",
                "相同内容。",
                "[]"));
        BlockContext earlier = context(block(
                "earlier",
                1,
                "同一章节",
                "同一小节",
                "相同内容。",
                "[]"));
        Map<String, Double> vectors = new LinkedHashMap<>();
        vectors.put("file-1:later", 0.70d);
        vectors.put("file-1:earlier", 0.70d);

        BlockContext selected = List.of(later, earlier).stream()
                .sorted(TeacherResourceBlockSearchPolicy.representativeBlockComparator(
                        DOCUMENT,
                        vectors,
                        Map.of(),
                        "相同内容",
                        new String[]{"相同内容"}))
                .findFirst()
                .orElseThrow();

        assertThat(selected.block().blockId()).isEqualTo("earlier");
    }

    @Test
    void fixedSemanticAndLexicalFileQuotasAreAdmittedBeforeFusionFill() {
        List<String> admitted = TeacherResourceBlockSearchPolicy.admitFileCandidates(
                List.of("v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8", "v9"),
                List.of("l1", "l2", "l3", "l4", "l5"),
                List.of("t1"),
                Map.ofEntries(
                        Map.entry("v1", 0.10d),
                        Map.entry("v2", 0.09d),
                        Map.entry("v3", 0.08d),
                        Map.entry("v4", 0.07d),
                        Map.entry("v5", 0.06d),
                        Map.entry("v6", 0.05d),
                        Map.entry("v7", 0.04d),
                        Map.entry("v8", 0.03d),
                        Map.entry("l1", 0.02d),
                        Map.entry("l2", 0.019d),
                        Map.entry("l3", 0.018d),
                        Map.entry("l4", 0.017d),
                        Map.entry("t1", 0.016d)),
                8,
                4,
                12);

        assertThat(admitted).containsExactly(
                "v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8",
                "l1", "l2", "l3", "l4");
    }

    /**
     * 20260903 文件名锚点准入：期望文件已在 lexical 召回列表内、但被泛词文件挤出槽位的回归用例画像
     * （F049：查询"2025新高考一卷数学函数与数列" vs "2026年数学模拟题/2025年新高考一卷.md"）。
     */
    @Test
    void titleAnchorPromotesFilesWhoseNameSharesThreeCharRunWithQuery() {
        List<String> ordered = TeacherResourceBlockSearchPolicy.prioritizeTitleAnchors(
                List.of("generic-a", "generic-b", "exam-2025"),
                Map.of(
                        "generic-a", "函数/三次函数总结.md",
                        "generic-b", "三角函数/正弦函数不等式.md",
                        "exam-2025", "2026年数学模拟题/2025年新高考一卷.md"),
                "2025新高考一卷数学函数与数列");

        // "三次函数"/"函数" 与查询的最长共享片段不足 3 字（泛词不晋升），2025年新高考一卷 独占前位。
        assertThat(ordered).containsExactly("exam-2025", "generic-a", "generic-b");
    }

    @Test
    void titleAnchorIsOrderPreservingNoOpWithoutAnyMatch() {
        List<String> files = List.of("a", "b");
        List<String> ordered = TeacherResourceBlockSearchPolicy.prioritizeTitleAnchors(
                files, Map.of("a", "数列/数列求和.md", "b", "立体几何/体积.md"), "古典概型等可能基本事件个数");

        assertThat(ordered).containsExactly("a", "b");
    }

    @Test
    void titleAnchorIgnoresPunctuationWhitespaceAndFileExtensions() {
        List<String> ordered = TeacherResourceBlockSearchPolicy.prioritizeTitleAnchors(
                List.of("other", "paper"),
                Map.of("other", "概率统计/AI 古典概型.md", "paper", "！！！重要必看！！！.md"),
                "重要必看！导数专题复习");

        // 标点/扩展名剥离后 "重要必看" 4 字连续片段命中全角感叹号文件词干；AI古典概型 共享片段 <3 不晋升。
        assertThat(ordered).containsExactly("paper", "other");
    }

    /**
     * 20260903 一代一槽：重同步三代同路径 FILE 带着相同向量命中占满窗口（A/B 实测挤掉正确路径，
     * 2 条 @3 回归）。dedupe 后每路径只留首个，剩余槽位从完整 RRF 排名补齐不同路径。
     */
    @Test
    void sourcePathDedupeKeepsOneGenerationPerPathAndFillsFromRrfRanking() {
        List<String> admitted = List.of("gen3", "gen2", "gen1", "dup-a1", "other");
        Map<String, Double> rrf = new java.util.LinkedHashMap<>();
        rrf.put("gen3", 0.030d);
        rrf.put("gen2", 0.029d);
        rrf.put("gen1", 0.028d);
        rrf.put("dup-a1", 0.027d);
        rrf.put("other", 0.026d);
        rrf.put("dup-a2", 0.025d);
        rrf.put("filler", 0.024d);
        Map<String, String> pathByFile = Map.of(
                "gen3", "真题/2026一卷.md",
                "gen2", "真题/2026一卷.md",
                "gen1", "真题/2026一卷.md",
                "dup-a1", "函数/单调性.md",
                "dup-a2", "函数/单调性.md",
                "other", "概率/古典概型.md",
                "filler", "数列/求和.md");

        List<String> kept = TeacherResourceBlockSearchPolicy.dedupeAdmittedFilesBySourcePath(
                admitted, rrf, pathByFile, 5);

        // 真题路径只留 gen3 一代；dup-a2 与 dup-a1 同路径不重复占槽；filler 补足第 4 个不同路径。
        assertThat(kept).containsExactly("gen3", "dup-a1", "other", "filler");
    }

    @Test
    void sourcePathDedupeTreatsMissingPathAsUniqueFile() {
        List<String> kept = TeacherResourceBlockSearchPolicy.dedupeAdmittedFilesBySourcePath(
                List.of("a", "b"), Map.of("a", 0.01d, "b", 0.02d), Map.of("a", ""), 8);

        // 无 sourcePath 的行按自身 id 去重，宁可各占槽也不吞并未知文件。
        assertThat(kept).containsExactly("a", "b");
    }

    @Test
    void overlappingRouteFilesUseOnePhysicalAdmissionSlot() {
        List<String> admitted = TeacherResourceBlockSearchPolicy.admitFileCandidates(
                List.of("shared", "v2", "v3"),
                List.of("shared", "l2", "l3", "l4"),
                List.of("tag-only"),
                Map.of("shared", 0.1d, "v2", 0.09d, "v3", 0.08d, "l2", 0.07d, "l3", 0.06d, "l4", 0.05d, "tag-only", 0.04d),
                8,
                4,
                12);

        assertThat(admitted).containsExactly("shared", "v2", "v3", "l2", "l3", "l4", "tag-only");
    }

    @Test
    void lowConfidenceRerankScoresAbstainWithoutRejectingCalibratedPositive() {
        assertThat(TeacherResourceBlockSearchPolicy.shouldAbstain(
                List.of(-6.61d, -6.70d), -6.60d, -6.00d, 0.15d)).isTrue();
        assertThat(TeacherResourceBlockSearchPolicy.shouldAbstain(
                List.of(-6.02d, -6.10d), -6.60d, -6.00d, 0.15d)).isTrue();
        assertThat(TeacherResourceBlockSearchPolicy.shouldAbstain(
                List.of(-6.10d, -6.30d), -6.60d, -6.00d, 0.15d)).isFalse();
        assertThat(TeacherResourceBlockSearchPolicy.shouldAbstain(
                List.of(-5.00d, -5.01d), -6.60d, -6.00d, 0.15d)).isFalse();
    }

    private static BlockContext context(TeacherDocumentBlockResponse block) {
        return TeacherResourceBlockSearchPolicy.toContext(DOCUMENT, block);
    }

    private static TeacherDocumentBlockResponse block(
            String id,
            int order,
            String chapter,
            String section,
            String text,
            String tags) {
        return new TeacherDocumentBlockResponse(
                id,
                "file-1",
                "file-1:" + id,
                "text",
                order,
                chapter,
                section,
                null,
                null,
                "",
                "reference",
                text,
                text,
                "[]",
                "[]",
                "[]",
                tags,
                "",
                1.0d,
                "active");
    }
}
