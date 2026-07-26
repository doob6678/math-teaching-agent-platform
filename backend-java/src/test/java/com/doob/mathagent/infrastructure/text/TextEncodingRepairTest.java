package com.doob.mathagent.infrastructure.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TextEncodingRepairTest {

    @Test
    void repairsUtf8ChineseTextMisreadAsLatin1() {
        String mojibake = new String(
                "高中数学题：已知函数 f(x)=x^2-4x+3".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.ISO_8859_1);

        String repaired = TextEncodingRepair.repairMojibake(mojibake);

        assertThat(repaired).contains("高中数学题", "已知函数");
        assertThat(repaired).doesNotContain("é«");
    }

    @Test
    void repairsLatin1MojibakeObservedFromLiveVisionResponse() {
        String mojibake = new String(
                "\u9ad8\u4e2d\u6570\u5b66\u9898\uff1a\n\u5df2\u77e5\u51fd\u6570 f(x)=x^2-4x+3"
                        .getBytes(StandardCharsets.UTF_8),
                StandardCharsets.ISO_8859_1);

        String repaired = TextEncodingRepair.repairMojibake(mojibake);

        assertThat(repaired).contains("\u9ad8\u4e2d\u6570\u5b66\u9898", "\u5df2\u77e5\u51fd\u6570");
        assertThat(repaired).doesNotContain("\u00e9\u00ab", "\u00e4\u00b8");
    }

    @Test
    void leavesNormalChineseTextUnchanged() {
        String normal = "高中数学题：已知函数 f(x)=x^2-4x+3";

        assertThat(TextEncodingRepair.repairMojibake(normal)).isEqualTo(normal);
    }
}
