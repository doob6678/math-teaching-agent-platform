package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.service.MultiQuestionTextParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the deterministic MCP batch-question contract before a live model receives the prompt.
 */
class MultiQuestionTextParserTest {

    @Test
    void prioritizesStructuredQuestionsAndPreservesFormulaSpaces() {
        List<String> questions = MultiQuestionTextParser.parse(
                List.of("  已知 $a b = 1$，求 $a+b$。  ", "求函数 $f(x)=x^2$ 的最小值。"),
                "第1题：this fallback must not be selected");

        assertThat(questions).containsExactly("已知 $a b = 1$，求 $a+b$。", "求函数 $f(x)=x^2$ 的最小值。");
    }

    @Test
    void splitsOnlyExplicitStandaloneMarkersOrQuestionHeadings() {
        List<String> questions = MultiQuestionTextParser.parse(
                List.of(),
                "第1题 已知集合 A={1, 2}，求元素个数。\n"
                        + "第2题 在三角形 ABC 中，已知 $a b = 1$，求面积。\n"
                        + "---\n"
                        + "求函数 $f(x)=x^2$ 在区间内的最值。\n"
                        + "###\n"
                        + "求直线与圆的位置关系。");

        assertThat(questions).containsExactly(
                "第1题 已知集合 A={1, 2}，求元素个数。",
                "第2题 在三角形 ABC 中，已知 $a b = 1$，求面积。",
                "求函数 $f(x)=x^2$ 在区间内的最值。",
                "求直线与圆的位置关系。");
    }

    @Test
    void doesNotTreatWhitespaceOrBlankLinesAsQuestionBoundaries() {
        List<String> questions = MultiQuestionTextParser.parse(
                List.of(),
                "已知 $a b = 1$，其中 a 与 b 为实数。\n\n求 $a+b$ 的范围。");

        assertThat(questions).containsExactly("已知 $a b = 1$，其中 a 与 b 为实数。\n\n求 $a+b$ 的范围。");
    }
}
