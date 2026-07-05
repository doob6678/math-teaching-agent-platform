package com.doob.mathagent.teaching;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teaching.service.TeachingHandoutTemplateProfile;
import com.doob.mathagent.teaching.service.TeachingHandoutTemplateService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeachingHandoutTemplateServiceTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearConfigProperty() {
        System.clearProperty("math.agent.handout.template.skill.files");
    }

    @Test
    void loadsTemplateSkillsFromJsonObjectConfig() throws Exception {
        Path config = tempDir.resolve("handout-skills.json");
        Files.writeString(config, """
                {
                  "templates": [
                    {
                      "templateCode": "unit_test_skill_v1",
                      "displayName": "单元测试 Skill",
                      "sourceType": "skill_config",
                      "audience": "teacher",
                      "description": "来自测试配置",
                      "category": "动态模板",
                      "visualStyle": "测试版式",
                      "difficultyBands": ["基础", "提高"],
                      "tags": ["测试", "配置"],
                      "referenceTitle": "测试引用",
                      "referencePreview": "测试摘要",
                      "promptInstructions": "生成正式讲义，教师版给答案，学生版留白。"
                    }
                  ]
                }
                """);
        System.setProperty("math.agent.handout.template.skill.files", config.toString());

        TeachingHandoutTemplateService service = new TeachingHandoutTemplateService();

        assertThat(service.list())
                .anySatisfy(template -> {
                    assertThat(template.templateCode()).isEqualTo("unit_test_skill_v1");
                    assertThat(template.sourceType()).isEqualTo("skill_config");
                    assertThat(template.tags()).contains("测试", "配置");
                });
        TeachingHandoutTemplateProfile resolved = service.resolve("unit_test_skill_v1");
        assertThat(resolved.summary().displayName()).isEqualTo("单元测试 Skill");
        assertThat(resolved.promptInstructions()).contains("教师版给答案");
    }

    @Test
    void ignoresBrokenTemplateSkillConfig() throws Exception {
        Path config = tempDir.resolve("broken.json");
        Files.writeString(config, "{not-json");
        System.setProperty("math.agent.handout.template.skill.files", config.toString());

        TeachingHandoutTemplateService service = new TeachingHandoutTemplateService();

        assertThat(service.resolve("missing").summary().templateCode()).isEqualTo("default_standard");
    }
}
