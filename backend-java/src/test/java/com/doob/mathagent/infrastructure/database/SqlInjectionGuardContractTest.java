package com.doob.mathagent.infrastructure.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SqlInjectionGuardContractTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Path MAIN_RESOURCES = Path.of("src/main/resources");

    @Test
    void sqlInjectionGuardDocumentDefinesNonNegotiableRules() throws Exception {
        String document = Files.readString(Path.of("../文档/开发进度/SQL注入防护规则.md"));

        assertThat(document)
                .contains("禁止 `${}` 拼接用户输入")
                .contains("禁止前端传入排序 SQL")
                .contains("只允许后端枚举映射列名")
                .contains("QueryWrapper 字符串列名必须来自后端常量");
    }

    @Test
    void mapperLayerDoesNotUseAnnotationSqlOrXmlRawSubstitution() throws Exception {
        List<String> violations = sourceFiles().flatMap(path -> violations(path).stream()).toList();

        assertThat(violations).isEmpty();
    }

    /**
     * Lists source files that can contain Java or XML SQL definitions.
     */
    private static Stream<Path> sourceFiles() throws Exception {
        return Stream.concat(walk(MAIN_JAVA), walk(MAIN_RESOURCES))
                .filter(SqlInjectionGuardContractTest::isSqlBearingFile);
    }

    /**
     * Walks one source root when it exists.
     */
    private static Stream<Path> walk(Path root) throws Exception {
        return Files.exists(root) ? Files.walk(root) : Stream.empty();
    }

    /**
     * Returns SQL injection guard violations for one file.
     */
    private static List<String> violations(Path path) {
        try {
            String text = Files.readString(path);
            return dangerousPatterns().stream()
                    .filter(pattern -> pattern.matcher(text).find())
                    .map(pattern -> path + " matches " + pattern.pattern())
                    .toList();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to scan " + path, exception);
        }
    }

    /**
     * Raw SQL features are forbidden unless a future test adds a narrow audited exception.
     */
    private static List<Pattern> dangerousPatterns() {
        return List.of(
                Pattern.compile("@(?:Select|Update|Delete|Insert)\\s*\\("),
                Pattern.compile("\\$\\{"),
                Pattern.compile("\\.(?:last|apply|inSql|notInSql|exists|notExists)\\s*\\("));
    }

    /**
     * Keeps the guard focused on code that can create SQL rather than ordinary business methods or JSON templates.
     */
    private static boolean isSqlBearingFile(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.endsWith(".xml")
                || normalized.contains("/mapper/")
                || normalized.contains("/db/migration/")
                || normalized.matches(".*/MyBatis[A-Za-z0-9]+Store\\.java");
    }
}
