package com.doob.mathagent.retrieval;

import java.util.List;

public record TextbookSearchResponse(
        String query,
        int limit,
        String retrievalStrategy,
        int total,
        List<TextbookSearchHit> hits) {
}
