package com.doob.mathagent.retrieval;

import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.vector.service.VectorHttpTransport;
import com.doob.mathagent.vector.service.VectorIndexProperties;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 将规范试卷注册到统一文本检索通道。
 *
 * <p>该服务不实现任何向量 HTTP 协议：查询编码和 Milvus 搜索完全复用 {@link TextbookMilvusSearchClient}，
 * 仅声明本公开语料的 collection 并交给来源授权适配器归一化为教学证据。</p>
 */
@Service
public class CanonicalMathPaperRetrievalService {
    private static final int MAX_RESULTS = 12;
    private static final int AUTHORIZATION_CANDIDATE_LIMIT = 50;
    private final TextbookMilvusSearchClient milvusSearchClient;
    private final CanonicalMathPaperCorpusAdapter corpusAdapter;
    private final String collectionName;

    public CanonicalMathPaperRetrievalService(
            VectorIndexProperties vectorProperties,
            VectorHttpTransport transport,
            CanonicalMathPaperCorpusAdapter corpusAdapter,
            @Value("${math-agent.teaching.canonical-paper.collection-name:gaokao_math}") String collectionName) {
        this.milvusSearchClient = new TextbookMilvusSearchClient(vectorProperties, transport);
        this.corpusAdapter = corpusAdapter;
        this.collectionName = collectionName == null || collectionName.isBlank()
                ? "gaokao_math" : collectionName.strip();
    }

    /** 返回统一来源授权后的公共试卷证据，不向教学层暴露向量实现或语料位置。 */
    public List<TeachingEvidence> search(String query, int limit) {
        String normalizedQuery = query == null ? "" : query.strip();
        if (normalizedQuery.isBlank()) {
            return List.of();
        }
        if (!isReady()) {
            return List.of();
        }
        int requestedLimit = Math.max(1, Math.min(MAX_RESULTS, limit));
        // Historical collections contain unpublished rows ahead of the published corpus. Recall the bounded
        // Milvus window first, then let the manifest-backed adapter enforce the authoritative source boundary.
        return corpusAdapter.adapt(milvusSearchClient.searchTextCollection(
                collectionName, normalizedQuery, Math.max(requestedLimit, AUTHORIZATION_CANDIDATE_LIMIT), List.of()))
                .stream()
                .limit(requestedLimit)
                .toList();
    }

    /** Optional canonical evidence is usable only when its owner published both corpus and loaded collection. */
    boolean isReady() {
        return corpusAdapter.hasPublishedCorpus() && milvusSearchClient.collectionExists(collectionName);
    }
}
