package com.doob.mathagent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teacher.search.TeacherResourceBlockSearchResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class WritingEvidenceContextFormatterTest {

    @Test
    void keepsReadableFeishuCitationTextAndAuthorizedImageUriInWriterContext() {
        TeacherResourceBlockSearchResponse.Hit hit = new TeacherResourceBlockSearchResponse.Hit(
                "shared-root-document",
                "高中数学全局共享资料",
                "feishu",
                "TENANT_PUBLIC",
                "block-vector-area",
                "text",
                7,
                "解三角形",
                "向量面积",
                null,
                "shared/triangle.md",
                "reference",
                List.of("向量", "面积"),
                List.of("block-vector-area"),
                "向量面积公式可由叉积与正弦关系统一理解。",
                "向量面积公式",
                0.98,
                List.of("asset-1"),
                List.of(new TeacherResourceBlockSearchResponse.AssetRef(
                        "asset-1", "/api/teacher-assets/asset-1", "image/png", "triangle.png", "shared/triangle.png", 2)));

        String context = WritingEvidenceContextFormatter.format(List.of(hit), 1_200, 2);

        assertThat(context).contains(
                "资料来源：高中数学全局共享资料",
                "文档=shared-root-document",
                "块=block-vector-area",
                "向量面积公式可由叉积与正弦关系统一理解。",
                "TEACHER_IMAGE: /api/teacher-assets/asset-1");
    }
}
