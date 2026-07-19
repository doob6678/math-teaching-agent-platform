package com.doob.mathagent.vector.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Configuration for real embedding and Milvus indexing.
 */
@ConfigurationProperties(prefix = "math-agent.vector-index")
public record VectorIndexProperties(
        boolean enabled,
        String milvusUri,
        String milvusToken,
        String collectionName,
        String studentMemoryCollectionName,
        int dimension,
        String embeddingBaseUrl,
        String embeddingApiKey,
        String embeddingModel,
        int requestTimeoutMs) {

    /** Pins Spring property binding to the full production configuration shape. */
    @ConstructorBinding
    public VectorIndexProperties {
    }

    public VectorIndexProperties(
            boolean enabled,
            String milvusUri,
            String milvusToken,
            String collectionName,
            int dimension,
            String embeddingBaseUrl,
            String embeddingApiKey,
            String embeddingModel,
            int requestTimeoutMs) {
        this(enabled, milvusUri, milvusToken, collectionName, "math_agent_student_memories_bge", dimension,
                embeddingBaseUrl, embeddingApiKey, embeddingModel, requestTimeoutMs);
    }

    public String normalizedCollectionName() {
        return collectionName == null || collectionName.isBlank() ? "math_agent_teacher_text_blocks_bge" : collectionName.strip();
    }

    public String normalizedStudentMemoryCollectionName() {
        return studentMemoryCollectionName == null || studentMemoryCollectionName.isBlank()
                ? "math_agent_student_memories_bge"
                : studentMemoryCollectionName.strip();
    }

    public int normalizedDimension() {
        return dimension <= 0 ? 512 : dimension;
    }

    public int normalizedTimeoutMs() {
        return requestTimeoutMs <= 0 ? 30000 : requestTimeoutMs;
    }

    public boolean fullyConfigured() {
        return enabled
                && hasText(milvusUri)
                && hasText(embeddingBaseUrl)
                && hasText(embeddingApiKey)
                && hasText(embeddingModel);
    }

    public void requireFullyConfigured() {
        if (!enabled) {
            throw new IllegalStateException("MATH_AGENT_VECTOR_INDEX_ENABLED must be true; vector indexing cannot be skipped");
        }
        if (!hasText(milvusUri)) {
            throw new IllegalStateException("MATH_AGENT_MILVUS_URI must point to a real Milvus endpoint");
        }
        if (!hasText(embeddingBaseUrl)) {
            throw new IllegalStateException("MATH_AGENT_EMBEDDING_BASE_URL must point to a real embedding API");
        }
        if (!hasText(embeddingApiKey)) {
            throw new IllegalStateException("MATH_AGENT_EMBEDDING_API_KEY or MATH_AGENT_WORKER_API_KEY must be configured");
        }
        if (!hasText(embeddingModel)) {
            throw new IllegalStateException("MATH_AGENT_EMBEDDING_MODEL must be configured");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
