package com.doob.mathagent.vector.service;

public record StudentMemorySearchHit(
        String memoryId,
        String content,
        double score) {
}
