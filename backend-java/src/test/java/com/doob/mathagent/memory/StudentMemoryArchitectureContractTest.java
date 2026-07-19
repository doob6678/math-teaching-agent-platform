package com.doob.mathagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.memory.entity.StudentMemoryEntryEntity;
import com.doob.mathagent.memory.mapper.StudentMemoryEntryMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StudentMemoryArchitectureContractTest {

    @Test
    void memoryModuleUsesRequiredLayeredPackages() {
        assertThat(Path.of("src/main/java/com/doob/mathagent/memory/controller")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/memory/service")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/memory/dto")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/memory/vo")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/memory/mapper")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/memory/entity")).isDirectory();
    }

    @Test
    void memoryMapperUsesMyBatisPlusEntity() {
        assertThat(BaseMapper.class).isAssignableFrom(StudentMemoryEntryMapper.class);
        assertThat(StudentMemoryEntryEntity.class.getAnnotation(TableName.class).value())
                .isEqualTo("student_memory_entry");
    }
}
