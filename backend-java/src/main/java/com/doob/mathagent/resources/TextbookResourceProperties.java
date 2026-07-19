package com.doob.mathagent.resources;

import java.nio.file.Path;
import java.util.Map;
import org.springframework.core.env.Environment;

public record TextbookResourceProperties(Path processedBooksRoot) {

    /**
     * 标准环境变量名：指向教材解析产物 processed_books 根目录。
     */
    static final String PROCESSED_BOOKS_ROOT_KEY = "MATH_AGENT_PROCESSED_BOOKS_ROOT";

    public TextbookResourceProperties {
        processedBooksRoot = processedBooksRoot.toAbsolutePath().normalize();
    }

    /**
     * 从进程环境变量读取教材资源目录，兼容命令行和部署脚本直接配置。
     */
    public static TextbookResourceProperties fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /**
     * 从 Spring Environment 读取教材资源目录，优先支持测试属性和启动参数，回退到系统环境变量。
     */
    public static TextbookResourceProperties fromSpringEnvironment(Environment environment) {
        String value = environment.getProperty(PROCESSED_BOOKS_ROOT_KEY);
        if (value == null || value.isBlank()) {
            value = environment.getProperty("math-agent.resources.processed-books-root");
        }
        return fromValue(value);
    }

    /**
     * 从可注入的环境变量 Map 读取教材资源目录，便于单元测试覆盖缺失配置。
     */
    public static TextbookResourceProperties fromEnvironment(Map<String, String> environment) {
        String value = environment.get(PROCESSED_BOOKS_ROOT_KEY);
        return fromValue(value);
    }

    /**
     * 校验并标准化教材资源目录配置。
     */
    private static TextbookResourceProperties fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + PROCESSED_BOOKS_ROOT_KEY);
        }
        return new TextbookResourceProperties(Path.of(value));
    }
}
