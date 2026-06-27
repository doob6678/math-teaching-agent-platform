package com.doob.mathagent.retrieval;

public record TextbookSearchRequest(String query, int limit) {

    public TextbookSearchRequest {
        query = query == null ? "" : query.strip();
        if (limit <= 0) {
            limit = 10;
        } else if (limit > 50) {
            limit = 50;
        }
    }
}
