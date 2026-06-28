package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.teacher.entity.TeacherSourceDocumentEntity;
import com.doob.mathagent.teacher.mapper.TeacherSourceDocumentMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TeacherResourceArchitectureContractTest {

    @Test
    void teacherResourceModuleUsesRequiredLayeredPackages() {
        assertThat(Path.of("src/main/java/com/doob/mathagent/teacher/controller")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/teacher/service")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/teacher/dto")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/teacher/vo")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/teacher/mapper")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/teacher/entity")).isDirectory();
    }

    @Test
    void sourceDocumentMapperUsesExistingSourceDocumentTable() {
        assertThat(BaseMapper.class).isAssignableFrom(TeacherSourceDocumentMapper.class);
        assertThat(TeacherSourceDocumentEntity.class.getAnnotation(TableName.class).value())
                .isEqualTo("source_document");
    }
}
