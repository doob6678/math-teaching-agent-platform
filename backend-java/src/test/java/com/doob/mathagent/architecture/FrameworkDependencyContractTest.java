package com.doob.mathagent.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FrameworkDependencyContractTest {

    @Test
    void pomUsesRequiredOfficialFrameworkStarters() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom)
                .contains("<artifactId>spring-ai-bom</artifactId>")
                .contains("<artifactId>spring-ai-starter-model-openai</artifactId>")
                .contains("<artifactId>spring-ai-pdf-document-reader</artifactId>")
                .contains("<artifactId>mybatis-plus-spring-boot3-starter</artifactId>")
                .contains("<artifactId>redisson-spring-boot-starter</artifactId>")
                .contains("<artifactId>sa-token-spring-boot3-starter</artifactId>");
    }

    @Test
    void teachingModuleHasLayeredControllerServiceDtoVoMapperPackages() {
        assertThat(Path.of("src/main/java/com/doob/mathagent/teaching/controller")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/teaching/service")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/teaching/dto")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/teaching/vo")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/teaching/mapper")).isDirectory();
    }
}
