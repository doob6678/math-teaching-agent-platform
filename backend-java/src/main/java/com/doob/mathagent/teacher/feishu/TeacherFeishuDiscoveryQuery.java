package com.doob.mathagent.teacher.feishu;

/**
 * Normalized Feishu discovery request passed to the client implementation.
 *
 * @param mode discovery mode, either list or search
 * @param keyword search keyword; blank for list mode
 * @param rootUrl root Feishu folder URL
 * @param listDepth depth for list mode
 * @param maxDepth depth for search mode
 */
public record TeacherFeishuDiscoveryQuery(
        String mode,
        String keyword,
        String rootUrl,
        int listDepth,
        int maxDepth) {
}
