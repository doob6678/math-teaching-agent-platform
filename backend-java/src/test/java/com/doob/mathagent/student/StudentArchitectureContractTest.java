package com.doob.mathagent.student;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.student.entity.StudentLearningSnapshotEntity;
import com.doob.mathagent.student.mapper.StudentLearningSnapshotMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StudentArchitectureContractTest {

    @Test
    void studentModuleUsesRequiredLayeredPackages() {
        assertThat(Path.of("src/main/java/com/doob/mathagent/student/controller")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/student/service")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/student/dto")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/student/vo")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/student/mapper")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/student/entity")).isDirectory();
    }

    @Test
    void studentSnapshotMapperUsesMyBatisPlus() {
        assertThat(BaseMapper.class).isAssignableFrom(StudentLearningSnapshotMapper.class);
        assertThat(StudentLearningSnapshotEntity.class.getAnnotation(TableName.class).value())
                .isEqualTo("student_learning_snapshot");
    }
}
