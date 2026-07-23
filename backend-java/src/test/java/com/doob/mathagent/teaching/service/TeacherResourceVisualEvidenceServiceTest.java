package com.doob.mathagent.teaching.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TeacherResourceVisualEvidenceServiceTest {

    @Test
    void keepsVisibleFactsButRemovesTransportLocationsFromVisionDescription() {
        String description = TeacherResourceVisualEvidenceService.sanitizeDescription(
                "图中可见区域标号 1、2、3、4、5。图片暂存于 C:/Users/doob/AppData/Local/Temp/map.jpg；"
                        + "来源 https://internal.example/private/map.jpg",
                160);

        assertThat(description)
                .contains("区域标号 1、2、3、4、5")
                .doesNotContain("C:/Users", "map.jpg", "https://", "internal.example");
    }

    @Test
    void capsUnboundedProviderTextAtConfiguredEvidenceBudget() {
        String description = TeacherResourceVisualEvidenceService.sanitizeDescription(
                "区域标号 1、2、3、4、5。图中出现三角形标记。后续文字不应进入模型上下文。",
                18);

        assertThat(description).hasSizeLessThanOrEqualTo(18).contains("区域标号");
    }
}
