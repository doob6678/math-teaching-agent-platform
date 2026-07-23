package com.doob.mathagent.knowledge.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared Chinese math search normalization for question-bank lookup.
 *
 * <p>Teaching tasks often submit natural sentences such as "学会空间向量大题怎么做".
 * A single SQL LIKE on that whole sentence misses real questions. Keep this logic centralized so
 * the management page, teaching workflow, and tests do not drift into different retrieval behavior.
 */
public final class QuestionBankSearchText {

    private static final List<String> CORE_TERMS = List.of(
            "函数", "函数新概念", "二次函数", "一元二次函数", "反比例函数", "定义域", "值域", "分段函数", "单调性", "奇偶性", "零点",
            "导数", "切线", "极值", "最值", "恒成立", "参数范围",
            "三角函数", "正弦定理", "余弦定理", "平面向量", "数量积",
            "空间向量", "立体几何", "线面角", "二面角", "法向量", "点到面距离",
            "棱柱", "三棱柱", "四棱柱", "棱锥", "四棱锥", "圆锥", "体积", "夹角", "垂直", "平行",
            "直线", "圆", "圆锥曲线", "椭圆", "双曲线", "抛物线", "渐近线", "离心率", "焦距",
            "数列", "等差数列", "等比数列", "递推", "错位相减", "裂项相消",
            "概率", "统计", "随机变量", "排列组合", "二项式", "涂色问题", "地图着色", "分类计数");

    private QuestionBankSearchText() {
    }

    /**
     * Builds bounded search candidates from one or more natural-language inputs.
     */
    public static List<String> candidateQueries(String... values) {
        Set<String> candidates = new LinkedHashSet<>();
        String combined = normalize(String.join(" ", values == null ? new String[0] : values));
        addIfUseful(candidates, combined);
        for (String term : CORE_TERMS) {
            if ("圆锥".equals(term) && combined.contains("圆锥曲线")) {
                continue;
            }
            if (combined.contains(term.toLowerCase())) {
                addIfUseful(candidates, term);
                expandDomainTerm(candidates, term);
            }
        }
        for (String token : splitNaturalTokens(combined)) {
            addIfUseful(candidates, token);
        }
        return candidates.stream().limit(12).toList();
    }

    /**
     * Keywords used inside one store-level query.
     */
    public static List<String> keywords(String query) {
        return candidateQueries(query).stream()
                .filter(keyword -> query == null || !keyword.equals(query.strip()))
                .limit(10)
                .toList();
    }

    /**
     * Returns concrete curriculum terms that must be present when a query names a specific topic.
     *
     * <p>The management search still uses the broader {@link #keywords(String)} list for recall, but a broad
     * expansion such as "函数" must not make an unrelated statistics or geometry row look like a quadratic-function
     * result. Keeping this vocabulary beside the shared query normalizer ensures the UI and teaching workflow apply
     * the same strict-topic boundary before semantic reranking.</p>
     */
    public static List<String> specificTopicTerms(String query) {
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return List.of();
        }
        return CORE_TERMS.stream()
                .filter(term -> term.length() >= 3)
                .filter(term -> normalized.contains(term.toLowerCase()))
                .filter(term -> !Set.of("函数", "三角函数", "空间向量", "立体几何", "平面向量", "圆锥曲线", "直线", "圆", "数列", "概率", "统计", "导数").contains(term))
                .distinct()
                .toList();
    }

    /**
     * Normalizes null and repeated whitespace.
     */
    public static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip().toLowerCase();
    }

    private static void expandDomainTerm(Set<String> candidates, String term) {
        if ("空间向量".equals(term) || "立体几何".equals(term)) {
            List.of("线面角", "二面角", "法向量", "点到面距离", "棱柱", "棱锥", "圆锥", "体积", "夹角", "垂直", "平行")
                    .forEach(value -> addIfUseful(candidates, value));
        }
        if ("双曲线".equals(term) || "圆锥曲线".equals(term)) {
            List.of("双曲线", "渐近线", "离心率", "焦距", "焦点", "标准方程")
                    .forEach(value -> addIfUseful(candidates, value));
        }
        if ("导数".equals(term)) {
            List.of("切线", "单调性", "极值", "最值", "恒成立", "参数范围")
                    .forEach(value -> addIfUseful(candidates, value));
        }
    }

    private static List<String> splitNaturalTokens(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String[] rawTokens = value.split("[\\s,，、。；;：:()（）【】\\[\\]{}]+");
        List<String> tokens = new ArrayList<>();
        for (String token : rawTokens) {
            String stripped = token.strip();
            if (stripped.length() >= 2 && stripped.length() <= 18 && !isStopToken(stripped)) {
                tokens.add(stripped);
            }
        }
        return tokens;
    }

    private static boolean isStopToken(String token) {
        return Set.of("学会", "学习", "讲解", "讲义", "题目", "问题", "大题", "小题", "开始", "基础", "方法", "怎么做")
                .contains(token);
    }

    private static void addIfUseful(Set<String> candidates, String value) {
        if (value == null) {
            return;
        }
        String normalized = normalize(value);
        if (normalized.length() >= 2 && normalized.length() <= 48 && !isStopToken(normalized)) {
            candidates.add(normalized);
        }
    }
}
