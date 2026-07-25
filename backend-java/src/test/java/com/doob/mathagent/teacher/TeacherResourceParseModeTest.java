package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teacher.dto.TeacherResourceRegistrationRequest;
import org.junit.jupiter.api.Test;

/** Verifies that the image-aware Markdown mode survives request normalization. */
class TeacherResourceParseModeTest {

    @Test
    void normalizesMarkdownAssetsModeForFeishuResources() {
        TeacherResourceRegistrationRequest request = new TeacherResourceRegistrationRequest(
                "feishu", "triangle", "https://my.feishu.cn/docx/example", null, "TEACHER_PRIVATE", "md", "MARKDOWN_ASSETS");

        assertThat(request.normalize().parseMode()).isEqualTo("MARKDOWN_ASSETS");
    }
}
