package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.teacher.search.entity.TeacherResourceSearchAuditHitEntity;
import com.doob.mathagent.teacher.search.entity.TeacherResourceSearchAuditLogEntity;
import com.doob.mathagent.teacher.document.entity.TeacherSourceDocumentEntity;
import com.doob.mathagent.teacher.search.mapper.TeacherResourceSearchAuditHitMapper;
import com.doob.mathagent.teacher.search.mapper.TeacherResourceSearchAuditLogMapper;
import com.doob.mathagent.teacher.document.mapper.TeacherSourceDocumentMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TeacherResourceArchitectureContractTest {

    @Test
    void teacherResourceModuleUsesRequiredLayeredPackages() {
        assertThat(Path.of("src/main/java/com/doob/mathagent/teacher/controller")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/teacher/service")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/teacher/dto")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/teacher/vo")).isDirectory();
        /*
         * The teacher module was split by responsibility so newly added persistence/search code does not drift back
         * into the former flat entity/mapper/service layout. Keep this contract aligned to the real package topology
         * rather than the historical one, otherwise follow-up changes will keep resurrecting deleted legacy classes.
         */
        assertThat(Path.of("src/main/java/com/doob/mathagent/teacher/asset")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/teacher/block")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/teacher/document")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/teacher/feishu")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/teacher/search")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/teacher/support")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/teacher/sync")).isDirectory();
    }

    @Test
    void sourceDocumentMapperUsesExistingSourceDocumentTable() {
        assertThat(BaseMapper.class).isAssignableFrom(TeacherSourceDocumentMapper.class);
        assertThat(TeacherSourceDocumentEntity.class.getAnnotation(TableName.class).value())
                .isEqualTo("source_document");
    }

    @Test
    void teacherResourceSearchAuditMappersUsePersistentAuditTables() {
        assertThat(BaseMapper.class).isAssignableFrom(TeacherResourceSearchAuditLogMapper.class);
        assertThat(BaseMapper.class).isAssignableFrom(TeacherResourceSearchAuditHitMapper.class);
        assertThat(TeacherResourceSearchAuditLogEntity.class.getAnnotation(TableName.class).value())
                .isEqualTo("teacher_resource_search_audit_log");
        assertThat(TeacherResourceSearchAuditHitEntity.class.getAnnotation(TableName.class).value())
                .isEqualTo("teacher_resource_search_audit_hit");
    }
}

