package com.doob.mathagent.student.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 决策/compose 流式正文投影的边界单测（方案二，2026-09-06）。
 *
 * <p>finalDraft 轮的决策 JSON 原文增量现在会进入投影层，必须证明：只放行 title/summary/items 的文本值、
 * 未闭合尾字段按安全前缀放行、action 轮（tools/queries）零正文、且跨增量不重复吐字。</p>
 */
class StudentExplanationControllerStreamProjectionTest {

    /** 把若干网络分片喂进与线上一致的累计缓冲，返回学生端收到的全部可见文本拼接。 */
    private static String feed(String... chunks) {
        StringBuilder cumulative = new StringBuilder();
        String[] sent = {""};
        StringBuilder visible = new StringBuilder();
        for (String chunk : chunks) {
            visible.append(StudentExplanationController.visibleProviderDelta(cumulative, sent, chunk));
        }
        return visible.toString();
    }

    @Test
    void streamsTitleAndSummaryProseFromPartialFinalDecisionJson() {
        String visible = feed(
                "{\"decision\":\"final\",\"tools\":[],\"queries\":[],\"conversationTitle\":\"水箱最低造价\",",
                "\"cards\":[{\"cardKey\":\"answer\",\"title\":\"",
                "第13题 解答\",\"summary\":\"设池底长宽",
                "为 x、y 米，最低总造价 297600 元。\",\"items\":[],\"sourceUris\":[],\"renderMode\":\"text\"}]}");
        assertThat(visible).contains("第13题 解答");
        assertThat(visible).contains("设池底长宽为 x、y 米，最低总造价 297600 元。");
        // 内部字段与 JSON 语法绝不进学生流。
        assertThat(visible).doesNotContain("decision").doesNotContain("final").doesNotContain("cardKey")
                .doesNotContain("conversationTitle").doesNotContain("sourceUris").doesNotContain("renderMode")
                .doesNotContain("{").doesNotContain("\"");
    }

    @Test
    void actionDecisionEmitsNoVisibleContent() {
        // 非 final 轮：工具名与检索词是内部决策字段，投影后学生端一个字符都看不到。
        String visible = feed(
                "{\"decision\":\"action\",",
                "\"tools\":[\"search_textbook\",\"search_teacher_resources\"],",
                "\"queries\":[\"二次函数顶点式\",\"配方法\"]}");
        assertThat(visible).isEmpty();
    }

    @Test
    void emitsEachCharacterOnceAcrossChunks() {
        StringBuilder cumulative = new StringBuilder();
        String[] sent = {""};
        String first = StudentExplanationController.visibleProviderDelta(cumulative, sent,
                "{\"cards\":[{\"title\":\"解答\",\"summary\":\"先求导");
        String second = StudentExplanationController.visibleProviderDelta(cumulative, sent,
                "数再求最值。\"}]}");
        assertThat(first).isEqualTo("解答\n先求导");
        assertThat(second).isEqualTo("数再求最值。");
    }

    @Test
    void extractsItemsArrayStrings() {
        String visible = feed("{\"cards\":[{\"title\":\"步骤\",\"summary\":\"\",\"items\":[\"设变量\",\"列不等式\",\"求最小值\"]}]}");
        assertThat(visible).contains("设变量").contains("列不等式").contains("求最小值");
    }
}
