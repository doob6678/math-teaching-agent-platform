package com.doob.mathagent.vector.service;

import java.util.List;

/** Browser/API request for text or direct image CLIP search over authorized teacher pages. */
public record TeacherResourceImageClipSearchRequest(
        String query,
        String image,
        Integer limit,
        List<String> documentIds) {
    public int normalizedLimit() {
        return Math.max(1, Math.min(50, limit == null ? 10 : limit));
    }
}
