package com.doob.mathagent.infrastructure.security;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * API 访问策略集合，按最长路径前缀匹配具体规则。
 */
public class ApiAccessPolicy {

    private final List<ApiAccessRule> rules;

    /**
     * 创建访问策略，并按路径前缀长度倒序保存规则，保证更具体规则优先。
     */
    public ApiAccessPolicy(List<ApiAccessRule> rules) {
        this.rules = rules.stream()
                .sorted(Comparator.comparingInt((ApiAccessRule rule) -> rule.pathPrefix().length()).reversed())
                .toList();
    }

    /**
     * 返回生产默认策略。
     */
    public static ApiAccessPolicy defaultRules() {
        return new ApiAccessPolicy(List.of(
                new ApiAccessRule("/api/system/health", ApiAccessLevel.PUBLIC, Set.of("*"), 120, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/auth/login", ApiAccessLevel.PUBLIC, Set.of("*"), 20, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/a2a/.well-known/agent-card.json", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 30, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/mcp/configuration", ApiAccessLevel.USER, Set.of("teacher", "admin"), 20, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/mcp/tools", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 30, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/security/capability-audits", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 60, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/security/capabilities", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 20, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/knowledge/points", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 30, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/knowledge/relations", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 30, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/question-bank/import/teacher-resources", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 10, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/question-bank/items", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 30, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/agents/model-catalog", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 30, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/agents/model-health", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 10, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/agents/traces", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 60, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/agents/execute", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 20, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/agents/run-plan", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 30, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/resources/textbooks/summary", ApiAccessLevel.PUBLIC, Set.of("*"), 120, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/students/dashboard", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 40, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/students/memory", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 40, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/teacher/resources", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 30, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/teaching/handouts/batch/zip", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 10, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/teaching/tasks", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 20, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/retrieval/audit", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 60, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/retrieval/textbooks/search", ApiAccessLevel.GUEST, Set.of("anonymous", "guest", "student", "teacher", "admin"), 30, Duration.ofMinutes(1))));
    }

    /**
     * 返回测试策略，允许测试指定低阈值验证限流行为。
     */
    public static ApiAccessPolicy defaultRulesForTests(int limit) {
        return new ApiAccessPolicy(List.of(
                new ApiAccessRule("/api/retrieval/audit", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), limit, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/retrieval/textbooks/search", ApiAccessLevel.GUEST, Set.of("anonymous", "guest", "student", "teacher", "admin"), limit, Duration.ofMinutes(1))));
    }

    /**
     * 按请求路径查找最匹配的访问规则。
     */
    public Optional<ApiAccessRule> findRule(String path) {
        return rules.stream()
                .filter(rule -> rule.matches(path))
                .findFirst();
    }
}
