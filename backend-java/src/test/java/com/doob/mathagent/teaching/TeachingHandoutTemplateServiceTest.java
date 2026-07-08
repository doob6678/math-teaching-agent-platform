package com.doob.mathagent.teaching.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Collectors;
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
                      "referencePath": "C:/Users/doob/Desktop/private/teacher-template.pdf",
                      "referencePreview": "测试摘要",
                      "blankSpaceEm": 11,
                      "questionGapEm": 5,
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
                    assertThat(template.referencePath()).isEqualTo("C:/Users/doob/Desktop/private/teacher-template.pdf");
                    assertThat(template.blankSpaceEm()).isEqualTo(11);
                    assertThat(template.questionGapEm()).isEqualTo(5);
                });
        TeachingHandoutTemplateProfile resolved = service.resolve("unit_test_skill_v1");
        assertThat(resolved.summary().displayName()).isEqualTo("单元测试 Skill");
        assertThat(resolved.summary().referencePath()).isEqualTo("C:/Users/doob/Desktop/private/teacher-template.pdf");
        assertThat(resolved.blankSpaceEm()).isEqualTo(11);
        assertThat(resolved.questionGapEm()).isEqualTo(5);
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
    void configuredTemplateSkillCannotOverrideBuiltInTemplateCode() throws Exception {
        Path config = tempDir.resolve("override.json");
        Files.writeString(config, """
                {
                  "templates": [
                    {
                      "templateCode": "default_standard",
                      "displayName": "覆盖内置模板",
                      "sourceType": "skill_config",
                      "audience": "teacher",
                      "description": "不应该覆盖内置模板",
                      "category": "危险配置",
                      "visualStyle": "覆盖版式",
                      "difficultyBands": ["压轴"],
                      "tags": ["覆盖"],
                      "referenceTitle": "不应该展示",
                      "referencePath": "C:/Users/doob/Desktop/private/override.pdf",
                      "referencePreview": "不应该展示",
                      "promptInstructions": "不应该进入提示词。"
                    }
                  ]
                }
                """);
        System.setProperty("math.agent.handout.template.skill.files", config.toString());

        TeachingHandoutTemplateService service = new TeachingHandoutTemplateService();
        TeachingHandoutTemplateProfile resolved = service.resolve("default_standard");

        assertThat(resolved.summary().templateCode()).isEqualTo("default_standard");
        assertThat(resolved.summary().displayName()).isEqualTo("标准讲义");
        assertThat(resolved.summary().sourceType()).isEqualTo("builtin");
        assertThat(resolved.summary().category()).isEqualTo("基础讲义");
        assertThat(resolved.summary().referencePath()).isNull();
        assertThat(resolved.summary().referenceTitle()).isNull();
        assertThat(resolved.promptInstructions())
                .contains("标准数学讲义")
                .doesNotContain("不应该进入提示词");
    }

    @Test
    void exposesReferencePathsOnlyForPdfBackedTemplates() {
        TeachingHandoutTemplateService service = new TeachingHandoutTemplateService();

        assertThat(service.list())
                .isNotEmpty()
                .anySatisfy(template -> {
                    if ("local_inverse_student_sample_v1".equals(template.templateCode())) {
                        assertThat(template.referencePath()).isNotBlank();
                    }
                })
                .anySatisfy(template -> {
                    if ("space_vector_reference_v1".equals(template.templateCode())) {
                        assertThat(template.referencePath()).contains("参考讲义数学空间向量.pdf");
                    }
                })
                .anySatisfy(template -> {
                    if ("default_standard".equals(template.templateCode())) {
                        assertThat(template.referencePath()).isNull();
                    }
                });
    }

    @Test
    void suppressesDuplicateTemplatesBackedBySameReferencePdf() {
        TeachingHandoutTemplateService service = new TeachingHandoutTemplateService();

        assertThat(service.list())
                .anySatisfy(template -> assertThat(template.templateCode()).isEqualTo("inverse_real_student_reference_v1"))
                .noneSatisfy(template -> assertThat(template.templateCode()).isEqualTo("local_inverse_student_sample_v1"));
        assertThat(service.list().stream()
                .map(template -> referenceIdentity(template.referenceTitle(), template.referencePath()))
                .filter(identity -> !identity.isBlank())
                .collect(Collectors.groupingBy(identity -> identity, Collectors.counting())))
                .allSatisfy((identity, count) -> assertThat(count)
                        .as("duplicate handout reference %s", identity)
                        .isEqualTo(1L));
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
                    assertThat(template.promptInstructions())
                            .contains("系统渲染负责", "A 基础", "B 提高", "C 压轴", "真实题库题目", "token")
                            .doesNotContain("页眉", "页脚", "颜色");
                });
    }

    private static String referenceIdentity(String referenceTitle, String referencePath) {
        String value = referencePath == null || referencePath.isBlank() ? referenceTitle : referencePath;
        if (value == null) {
            return "";
        }
        if (value.isBlank()) {
            return "";
        }
        String normalized = value.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        if (index >= 0) {
            normalized = normalized.substring(index + 1);
        }
        return normalized
                .replaceFirst("(?i)\\.pdf$", "")
                .replaceAll("\\d{6,}$", "")
                .replaceAll("[\\s_\\-]+", "")
                .toLowerCase(Locale.ROOT);
    }
}
