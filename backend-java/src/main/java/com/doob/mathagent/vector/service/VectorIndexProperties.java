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
        String textbookTextCollectionName,
        String textbookImageCollectionName,
        int textbookTextDimension,
        int textbookImageDimension,
        int textbookImageQueryDimension,
        int dimension,
        String embeddingBaseUrl,
        String embeddingApiKey,
        String embeddingModel,
        int requestTimeoutMs,
        int embeddingBatchSize,
        boolean teacherImageClipEnabled,
        String teacherImageCollectionName,
        int teacherImageDimension,
        int teacherImageQueryDimension,
        Boolean childChunkSearchEnabled) {

    /** Pins Spring property binding to the full production configuration shape. */
    @ConstructorBinding
    public VectorIndexProperties {
    }

    public VectorIndexProperties(
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
        this(enabled, milvusUri, milvusToken, collectionName, studentMemoryCollectionName,
                "math_agent_textbook_pages_bge", "math_agent_textbook_pages_clip", 512, 768, 512, dimension,
                embeddingBaseUrl, embeddingApiKey, embeddingModel, requestTimeoutMs,
                10, false, "math_agent_teacher_page_assets_clip", 768, 512, null);
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
        this(enabled, milvusUri, milvusToken, collectionName, "math_agent_student_memories_bge",
                "math_agent_textbook_pages_bge", "math_agent_textbook_pages_clip", 512, 768, 512, dimension,
                embeddingBaseUrl, embeddingApiKey, embeddingModel, requestTimeoutMs,
                10, false, "math_agent_teacher_page_assets_clip", 768, 512, null);
    }

    public String normalizedCollectionName() {
        return collectionName == null || collectionName.isBlank() ? "math_agent_teacher_text_blocks_bge" : collectionName.strip();
    }

    /**
     * 20260830 parent-child retrieval: child chunks live in a derived sibling collection with the same generic
     * schema. The name is derived, not configured, so rollback is a pure search-route switch and the production
     * block collection is never rebuilt or deleted.
     */
    public String normalizedChildCollectionName() {
        return normalizedCollectionName() + "_child";
    }

    /**
     * 20260830：子块融合路由开关（默认开）。实测融合路由 gate 全过但 doc@3 比纯块路由低约 3pp，
     * 保留一行配置回滚能力：math-agent.vector-index.child-chunk-search-enabled: false。
     */
    public boolean normalizedChildChunkSearchEnabled() {
        return childChunkSearchEnabled == null || childChunkSearchEnabled;
    }

    public String normalizedStudentMemoryCollectionName() {
        return studentMemoryCollectionName == null || studentMemoryCollectionName.isBlank()
                ? "math_agent_student_memories_bge"
                : studentMemoryCollectionName.strip();
    }

    /** Textbook BGE pages are isolated from teacher text because their lifecycle and source identity differ. */
    public String normalizedTextbookTextCollectionName() {
        return textbookTextCollectionName == null || textbookTextCollectionName.isBlank()
                ? "math_agent_textbook_pages_bge" : textbookTextCollectionName.strip();
    }

    /** Textbook CLIP pages must never share a collection with 512-dimensional BGE vectors. */
    public String normalizedTextbookImageCollectionName() {
        return textbookImageCollectionName == null || textbookImageCollectionName.isBlank()
                ? "math_agent_textbook_pages_clip" : textbookImageCollectionName.strip();
    }

    public int normalizedTextbookTextDimension() {
        return textbookTextDimension <= 0 ? 512 : textbookTextDimension;
    }

    public int normalizedTextbookImageDimension() {
        return textbookImageDimension <= 0 ? 768 : textbookImageDimension;
    }

    /** Effective CLIP prefix dimension used by the live worker before zero-padding into the stored image schema. */
    public int normalizedTextbookImageQueryDimension() {
        return textbookImageQueryDimension <= 0 ? normalizedTextbookImageDimension() : textbookImageQueryDimension;
    }

    /** Keeps private teacher-page CLIP vectors isolated from public textbook images. */
    public String normalizedTeacherImageCollectionName() {
        return teacherImageCollectionName == null || teacherImageCollectionName.isBlank()
                ? "math_agent_teacher_page_assets_clip" : teacherImageCollectionName.strip();
    }

    public int normalizedTeacherImageDimension() {
        return teacherImageDimension <= 0 ? 768 : teacherImageDimension;
    }

    public int normalizedTeacherImageQueryDimension() {
        return teacherImageQueryDimension <= 0 ? 512 : teacherImageQueryDimension;
    }

    public int normalizedDimension() {
        return dimension <= 0 ? 512 : dimension;
    }

    public int normalizedTimeoutMs() {
        return requestTimeoutMs <= 0 ? 30000 : requestTimeoutMs;
    }

    /** Provider-safe maximum number of text inputs sent in one embedding request. */
    public int normalizedEmbeddingBatchSize() {
        return embeddingBatchSize <= 0 ? 10 : embeddingBatchSize;
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
