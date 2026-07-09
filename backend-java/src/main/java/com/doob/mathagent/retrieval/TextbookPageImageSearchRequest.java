package com.doob.mathagent.retrieval;

import java.util.List;

/**
 * Request contract for CLIP-based public textbook page-image retrieval.
 *
 * @param query optional text query; sent to the worker as CLIP text embedding input
 * @param image optional base64 or data URL image query; sent to the worker as CLIP image embedding input
 * @param limit maximum hit count
 * @param docIds optional textbook doc ids to narrow the public textbook scope
 */
public record TextbookPageImageSearchRequest(
        String query,
        String image,
        int limit,
        List<String> docIds) {

    public int normalizedLimit() {
        return limit <= 0 ? 10 : Math.min(limit, 50);
    }
}
