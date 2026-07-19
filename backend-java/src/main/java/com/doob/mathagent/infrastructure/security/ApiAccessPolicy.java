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
                /*
                 * Keep throttling only on endpoints that actually trigger model or inference work. Ordinary business
                 * APIs such as capability preparation, resource CRUD, search, and dashboard reads stay unlimited here;
                 * upstream gateway or login-specific abuse controls can be handled separately if needed.
                 */
                new ApiAccessRule("/api/system/health", ApiAccessLevel.PUBLIC, Set.of("*"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/system/runtime", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/auth/register", ApiAccessLevel.PUBLIC, Set.of("*"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/auth/session", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/auth/login", ApiAccessLevel.PUBLIC, Set.of("*"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/a2a/.well-known/agent-card.json", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/mcp/configuration/me", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/mcp/keys/", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/mcp/keys", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/mcp/tools/", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/mcp/tools", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/mcp", ApiAccessLevel.GUEST, Set.of("anonymous", "guest", "student", "teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/security/capability-audits", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/security/capabilities", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/knowledge/graph/spine", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/knowledge/points", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/knowledge/relations", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/vector-index/status", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/vector-index/teacher-resources", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 5, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/question-bank/import/teacher-resources", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/question-bank/items", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/agents/model-catalog", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/agents/registry", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/agents/knowledge-retrieval", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 20, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/agents/model-health", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/agents/traces", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/agents/execute", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 20, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/agents/run-plan", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 30, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/agents/writing/courseware/async", ApiAccessLevel.USER, Set.of("teacher", "admin"), 10, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/agents/writing/courseware", ApiAccessLevel.USER, Set.of("teacher", "admin"), 10, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/agents/writing", ApiAccessLevel.USER, Set.of("teacher", "admin"), 120, Duration.ofMinutes(1)),
                /*
                 * Public textbook metadata and page-image reads share the same processed_books backing store and never
                 * expose tenant-private material. Keep the whole /api/resources/textbooks/ prefix public so CLIP page
                 * search hits can resolve controlled image URLs without requiring a login or leaking local paths.
                 */
                new ApiAccessRule("/api/resources/textbooks/", ApiAccessLevel.PUBLIC, Set.of("*"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/students/explanations", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 20, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/students/dashboard", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/students/memory", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/teacher/resources/search/audit", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/teacher/resources/search", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/teacher/resources", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/teaching/handout-templates", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/teaching/handouts/batch/zip", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/teaching/tasks", ApiAccessLevel.USER, Set.of("student", "teacher", "admin"), 20, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/retrieval/audit", ApiAccessLevel.ADMIN, Set.of("teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/retrieval/textbooks/search", ApiAccessLevel.GUEST, Set.of("anonymous", "guest", "student", "teacher", "admin"), 0, Duration.ofMinutes(1)),
                new ApiAccessRule("/api/retrieval/textbooks/page-search", ApiAccessLevel.GUEST, Set.of("anonymous", "guest", "student", "teacher", "admin"), 20, Duration.ofMinutes(1))));
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
