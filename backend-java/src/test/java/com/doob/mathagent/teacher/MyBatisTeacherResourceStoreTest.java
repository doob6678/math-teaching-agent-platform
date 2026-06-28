package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.doob.mathagent.teacher.entity.TeacherSourceDocumentEntity;
import com.doob.mathagent.teacher.mapper.TeacherSourceDocumentMapper;
import com.doob.mathagent.teacher.service.MyBatisTeacherResourceStore;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MyBatisTeacherResourceStoreTest {

    @Test
    void saveConvertsResponseToSourceDocumentEntity() {
        CapturingMapper mapper = new CapturingMapper();
        MyBatisTeacherResourceStore store = new MyBatisTeacherResourceStore(mapper.proxy());

        TeacherResourceDocumentResponse saved = store.save(new TeacherResourceDocumentResponse(
                "doc-100",
                "tenant-a",
                "teacher-1",
                "feishu",
                "飞书题库",
                "https://example.feishu.cn/docs/doc1",
                null,
                "TEACHER_PRIVATE",
                "registered",
                "pending",
                "pending",
                "waiting_rebuild",
                List.of()));

        assertThat(saved.documentId()).isEqualTo("doc-100");
        assertThat(mapper.inserted.getTitle()).isEqualTo("飞书题库");
        assertThat(mapper.inserted.getCreatedBy()).isEqualTo("teacher-1");
        assertThat(mapper.inserted.getMetadataJson()).contains("waiting_rebuild");
    }

    @Test
    void listVisibleMapsEntitiesAndKeepsActiveTeacherOwnedResources() {
        CapturingMapper mapper = new CapturingMapper();
        mapper.rows.add(entity(10L, "tenant-a", "teacher-1", "active", "函数题库"));
        mapper.rows.add(entity(11L, "tenant-a", "teacher-2", "active", "其他教师题库"));
        mapper.rows.add(entity(12L, "tenant-a", "teacher-1", "archived", "已归档题库"));
        MyBatisTeacherResourceStore store = new MyBatisTeacherResourceStore(mapper.proxy());

        List<TeacherResourceDocumentResponse> visible = store.listVisible("tenant-a", "teacher", "teacher-1");

        assertThat(visible).extracting(TeacherResourceDocumentResponse::documentId).containsExactly("10");
        assertThat(visible.getFirst().indexStatus()).isEqualTo("waiting_rebuild");
    }

    @Test
    void archiveUpdatesExistingEntityStatus() {
        CapturingMapper mapper = new CapturingMapper();
        mapper.rows.add(entity(20L, "tenant-a", "teacher-1", "registered", "空间向量讲义"));
        MyBatisTeacherResourceStore store = new MyBatisTeacherResourceStore(mapper.proxy());

        TeacherResourceDocumentResponse archived = store.save(new TeacherResourceDocumentResponse(
                "20",
                "tenant-a",
                "teacher-1",
                "local_path",
                "空间向量讲义",
                null,
                "C:/math/vector",
                "MATH_VIP",
                "archived",
                "pending",
                "pending",
                "archived",
                List.of()));

        assertThat(archived.syncStatus()).isEqualTo("archived");
        assertThat(mapper.updated.getSyncStatus()).isEqualTo("archived");
        assertThat(mapper.updated.getMetadataJson()).contains("archived");
    }

    private static TeacherSourceDocumentEntity entity(
            Long id,
            String tenantId,
            String createdBy,
            String syncStatus,
            String title) {
        TeacherSourceDocumentEntity entity = new TeacherSourceDocumentEntity();
        entity.setId(id);
        entity.setTenantId(tenantId);
        entity.setCreatedBy(createdBy);
        entity.setSourceType("local_path");
        entity.setTitle(title);
        entity.setLocalPath("C:/math/" + id);
        entity.setPermissionScope("MATH_VIP");
        entity.setSyncStatus(syncStatus);
        entity.setParseStatus("pending");
        entity.setEmbeddingStatus("pending");
        entity.setMetadataJson("{\"indexStatus\":\"waiting_rebuild\"}");
        return entity;
    }

    private static class CapturingMapper {
        private final List<TeacherSourceDocumentEntity> rows = new ArrayList<>();
        private TeacherSourceDocumentEntity inserted;
        private TeacherSourceDocumentEntity updated;

        TeacherSourceDocumentMapper proxy() {
            return (TeacherSourceDocumentMapper) Proxy.newProxyInstance(
                    TeacherSourceDocumentMapper.class.getClassLoader(),
                    new Class<?>[] {TeacherSourceDocumentMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "insert" -> {
                            inserted = (TeacherSourceDocumentEntity) args[0];
                            yield 1;
                        }
                        case "updateById" -> {
                            updated = (TeacherSourceDocumentEntity) args[0];
                            yield 1;
                        }
                        case "selectById" -> rows.stream()
                                .filter(row -> row.getId().equals(Long.valueOf(String.valueOf(args[0]))))
                                .findFirst()
                                .orElse(null);
                        case "selectList" -> selectList((Wrapper<TeacherSourceDocumentEntity>) args[0]);
                        case "update" -> {
                            UpdateWrapper<TeacherSourceDocumentEntity> wrapper =
                                    (UpdateWrapper<TeacherSourceDocumentEntity>) args[1];
                            yield wrapper == null ? 0 : 1;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private List<TeacherSourceDocumentEntity> selectList(Wrapper<TeacherSourceDocumentEntity> ignored) {
            return rows.stream()
                    .filter(row -> "tenant-a".equals(row.getTenantId()))
                    .filter(row -> "teacher-1".equals(row.getCreatedBy()))
                    .filter(row -> !"archived".equals(row.getSyncStatus()))
                    .toList();
        }
    }
}
