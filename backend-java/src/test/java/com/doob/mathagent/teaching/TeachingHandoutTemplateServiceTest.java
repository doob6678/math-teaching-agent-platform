package com.doob.mathagent.teaching.service;

import static org.assertj.core.api.Assertions.assertThat;

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
        System.clearProperty("math.agent.handout.template.dirs");
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
                    assertThat(template.displayName()).isEqualTo("单元测试 Skill");
                    assertThat(template.tags()).contains("测试", "配置");
                });
        TeachingHandoutTemplateProfile resolved = service.resolve("unit_test_skill_v1");
        assertThat(resolved.summary().displayName()).isEqualTo("单元测试 Skill");
        assertThat(resolved.promptInstructions()).contains("教师版给答案", "学生版留白");
    }

    @Test
    void ignoresBrokenTemplateSkillConfig() throws Exception {
        Path config = tempDir.resolve("broken.json");
        Files.writeString(config, "{not-json");
        System.setProperty("math.agent.handout.template.skill.files", config.toString());

        TeachingHandoutTemplateService service = new TeachingHandoutTemplateService();

        assertThat(service.resolve("missing").summary().templateCode()).isEqualTo("default_standard");
    }

    @Test
    void defaultDesktopReferenceRootsIncludeLocalMathHandoutFolders() {
        assertThat(TeachingHandoutLocalReferenceScanner.defaultDesktopReferenceRoots())
                .anySatisfy(path -> assertThat(path.toString()).contains("02-专题讲义"))
                .anySatisfy(path -> assertThat(path.toString()).contains("03-综合复习与冲刺"))
                .anySatisfy(path -> assertThat(path.toString()).contains("07-地区试题"))
                .anySatisfy(path -> assertThat(path.toString()).contains("documents_full").contains("高中数学"))
                .anySatisfy(path -> assertThat(path.toString()).contains("高考历年真题"))
                .anySatisfy(path -> assertThat(path.toString()).contains("高考真题"))
                .anySatisfy(path -> assertThat(path.toString()).contains("xwechat_files"));
    }

    @Test
    void localReferenceScannerKeepsHighPriorityMathHandoutTemplates() throws Exception {
        Path referenceRoot = tempDir.resolve("reference-root");
        Path ordinaryFolder = referenceRoot.resolve("普通资料");
        Path zhaoFolder = referenceRoot.resolve("zhao_lixian_gaokao_topic");
        Files.createDirectories(ordinaryFolder);
        Files.createDirectories(zhaoFolder);
        for (int index = 0; index < 12; index += 1) {
            Files.writeString(ordinaryFolder.resolve("普通讲义" + index + ".pdf"), "not a real pdf");
        }
        Files.writeString(zhaoFolder.resolve("zhao_lixian_daoshu_gaokao_handout.pdf"), "not a real pdf");
        System.setProperty("math.agent.handout.template.dirs", referenceRoot.toString());

        assertThat(new TeachingHandoutLocalReferenceScanner().scan())
                .anySatisfy(template -> {
                    assertThat(template.summary().displayName()).contains("zhao_lixian_daoshu_gaokao_handout");
                    assertThat(template.summary().tags()).contains("赵礼显", "导数", "高考");
                    assertThat(template.promptInstructions()).contains("PDF", "token");
                });
    }
}
