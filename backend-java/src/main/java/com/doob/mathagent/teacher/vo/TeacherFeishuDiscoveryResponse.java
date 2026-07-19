package com.doob.mathagent.teacher.vo;

import java.util.List;

/**
 * Feishu remote discovery response used before downloading a folder or document.
 *
 * @param queryId server-generated query id for UI tracing
 * @param mode discovery mode reported by the Feishu script, such as list_root or search_root
 * @param rootUrl root Feishu folder URL used for discovery
 * @param keyword keyword used by search mode
 * @param depth requested traversal depth after server-side clamping
 * @param candidateCount number of returned candidates
 * @param candidates Feishu remote resources that may later be registered or downloaded
 * @param status discovery status, such as ok
 * @param message human-readable summary without credentials
 */
public record TeacherFeishuDiscoveryResponse(
        String queryId,
        String mode,
        String rootUrl,
        String keyword,
        int depth,
        int candidateCount,
        List<Candidate> candidates,
        String status,
        String message) {

    /**
     * Single Feishu remote candidate.
     *
     * @param resourceType Feishu resource type, such as folder, docx, or file
     * @param token Feishu resource token used to build canonical URLs
     * @param name display name from Feishu metadata
     * @param path path relative to the discovery root
     * @param url browser URL that can be registered as a teacher source
     * @param depth folder depth relative to root
     * @param downloadable whether the downloader can later fetch/export the resource
     */
    public record Candidate(
            String resourceType,
            String token,
            String name,
            String path,
            String url,
            int depth,
            boolean downloadable) {
    }
}
