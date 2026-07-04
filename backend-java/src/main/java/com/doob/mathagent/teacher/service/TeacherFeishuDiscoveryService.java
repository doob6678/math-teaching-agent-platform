package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherFeishuDiscoveryResponse;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Server-side Feishu discovery service that applies role checks and input limits before network access.
 */
@Service
public class TeacherFeishuDiscoveryService {

    private static final int DEFAULT_LIST_DEPTH = 1;
    private static final int MAX_LIST_DEPTH = 3;
    private static final int DEFAULT_SEARCH_DEPTH = 5;
    private static final int MAX_SEARCH_DEPTH = 8;

    private final TeacherFeishuDiscoveryClient discoveryClient;

    /**
     * Creates a Feishu discovery service.
     *
     * @param discoveryClient Feishu discovery client
     */
    public TeacherFeishuDiscoveryService(TeacherFeishuDiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
    }

    /**
     * Discovers Feishu folder candidates for teacher/admin subjects only.
     *
     * @param tenantId backend-resolved tenant id
     * @param viewerRole backend-resolved role
     * @param viewerSubjectId backend-resolved subject id
     * @param mode discovery mode, list or search
     * @param keyword search keyword
     * @param rootUrl root Feishu folder URL
     * @param listDepth requested list depth
     * @param maxDepth requested search depth
     * @return Feishu discovery response
     */
    public TeacherFeishuDiscoveryResponse discover(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String mode,
            String keyword,
            String rootUrl,
            int listDepth,
            int maxDepth) {
        requireText(tenantId, "tenantId is required");
        String normalizedRole = requireText(viewerRole, "viewerRole is required").toLowerCase(Locale.ROOT);
        requireText(viewerSubjectId, "viewerSubjectId is required");
        requireTeacherOrAdmin(normalizedRole);
        String normalizedMode = normalizeMode(mode);
        String normalizedKeyword = textOrDefault(keyword, "");
        String normalizedRootUrl = requireText(rootUrl, "Feishu discovery rootUrl is required");
        if ("search".equals(normalizedMode) && normalizedKeyword.isBlank()) {
            throw new IllegalArgumentException("Feishu discovery search keyword is required");
        }
        TeacherFeishuDiscoveryQuery query = new TeacherFeishuDiscoveryQuery(
                normalizedMode,
                normalizedKeyword,
                normalizedRootUrl,
                clamp(listDepth, DEFAULT_LIST_DEPTH, MAX_LIST_DEPTH),
                clamp(maxDepth, DEFAULT_SEARCH_DEPTH, MAX_SEARCH_DEPTH));
        return discoveryClient.discover(query);
    }

    /**
     * Restricts discovery to teacher/admin because Feishu metadata may reveal private teacher resources.
     */
    private static void requireTeacherOrAdmin(String viewerRole) {
        if (!"teacher".equals(viewerRole) && !"admin".equals(viewerRole)) {
            throw new IllegalArgumentException("Feishu discovery requires teacher or admin role");
        }
    }

    /**
     * Normalizes supported discovery modes.
     */
    private static String normalizeMode(String mode) {
        String normalized = textOrDefault(mode, "list").toLowerCase(Locale.ROOT);
        if ("search".equals(normalized)) {
            return "search";
        }
        return "list";
    }

    /**
     * Clamps a positive integer request parameter to a bounded range.
     */
    private static int clamp(int value, int defaultValue, int maxValue) {
        if (value <= 0) {
            return defaultValue;
        }
        return Math.min(value, maxValue);
    }

    /**
     * Returns stripped text or a fallback when blank.
     */
    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    /**
     * Returns stripped text or fails when a network root must be explicit.
     */
    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
