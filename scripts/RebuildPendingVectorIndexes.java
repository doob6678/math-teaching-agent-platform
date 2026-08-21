package com.doob.mathagent.scripts;

import com.doob.mathagent.vector.service.VectorIndexService;
import com.doob.mathagent.teacher.document.TeacherResourceStore;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.util.List;

/**
 * 批量重建所有 pending 状态文档的向量索引
 * 
 * 运行方式：
 * cd backend-java
 * ./gradlew bootRun --args='--spring.main.web-application-type=none --spring.profiles.active=rebuild-vectors'
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.doob.mathagent")
public class RebuildPendingVectorIndexes {

    public static void main(String[] args) {
        System.setProperty("spring.main.web-application-type", "none");
        SpringApplication.run(RebuildPendingVectorIndexes.class, args);
    }

    @Bean
    public CommandLineRunner run(
            VectorIndexService vectorIndexService,
            TeacherResourceStore resourceStore) {
        return args -> {
            System.out.println("==========================================");
            System.out.println("批量重建 pending 文档的向量索引");
            System.out.println("==========================================");
            System.out.println();

            String tenantId = "default";
            String viewerRole = "admin";
            String viewerSubjectId = "system-admin";

            // 获取所有可见文档
            List<TeacherResourceDocumentResponse> allDocs = resourceStore.listVisible(
                    tenantId, viewerRole, viewerSubjectId);
            
            System.out.println("发现 " + allDocs.size() + " 个文档");

            // 筛选 pending 状态的文档
            List<TeacherResourceDocumentResponse> pendingDocs = allDocs.stream()
                    .filter(doc -> "pending".equals(doc.embeddingStatus()))
                    .toList();

            if (pendingDocs.isEmpty()) {
                System.out.println("✓ 没有发现 pending 状态的文档，所有向量索引都是最新的！");
                return;
            }

            System.out.println("发现 " + pendingDocs.size() + " 个 pending 状态的文档需要重建");
            System.out.println();

            int successCount = 0;
            int failCount = 0;

            for (TeacherResourceDocumentResponse doc : pendingDocs) {
                System.out.println("----------------------------------------");
                System.out.println("文档 ID: " + doc.documentId());
                System.out.println("文档标题: " + doc.title());
                System.out.println("来源类型: " + doc.sourceType());

                try {
                    var response = vectorIndexService.rebuildTeacherResource(
                            tenantId,
                            viewerRole,
                            viewerSubjectId,
                            doc.documentId());

                    if ("success".equals(response.status())) {
                        System.out.println("✓ 向量索引重建成功");
                        System.out.println("  已索引向量数: " + response.indexedCount());
                        System.out.println("  跳过向量数: " + response.skippedCount());
                        successCount++;
                    } else {
                        System.out.println("✗ 向量索引重建失败: " + response.message());
                        failCount++;
                    }
                } catch (Exception e) {
                    System.out.println("✗ 向量索引重建异常: " + e.getMessage());
                    failCount++;
                }

                System.out.println();
                Thread.sleep(500);
            }

            System.out.println("==========================================");
            System.out.println("批量重建完成！");
            System.out.println("==========================================");
            System.out.println("成功: " + successCount);
            System.out.println("失败: " + failCount);
            System.out.println("总计: " + pendingDocs.size());
        };
    }
}
