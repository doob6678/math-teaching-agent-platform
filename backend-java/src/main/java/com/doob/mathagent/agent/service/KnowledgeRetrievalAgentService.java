package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.KnowledgeRetrievalAgentRequest;
import com.doob.mathagent.agent.vo.KnowledgeEvidencePackResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.resources.TextbookResourceProperties;
import com.doob.mathagent.retrieval.CanonicalMathPaperRetrievalService;
import com.doob.mathagent.retrieval.RetrievalRequestContext;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.retrieval.TextbookSearchRequest;
import com.doob.mathagent.teaching.TeachingEvidence;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 执行统一公共语料检索，并将已授权命中转换为可复用的 Evidence Pack。 */
@Service
public class KnowledgeRetrievalAgentService {
    private final TextbookResourceProperties textbookProperties;
    private final TextbookRetrievalService retrievalService;
    private final CanonicalMathPaperRetrievalService canonicalMathPaperRetrievalService;

    @Autowired
    public KnowledgeRetrievalAgentService(
            TextbookResourceProperties textbookProperties,
            TextbookRetrievalService retrievalService,
            Optional<CanonicalMathPaperRetrievalService> canonicalMathPaperRetrievalService) {
        this.textbookProperties = textbookProperties;
        this.retrievalService = retrievalService;
        this.canonicalMathPaperRetrievalService = canonicalMathPaperRetrievalService.orElse(null);
    }

    /** 保留聚焦测试和旧调用方的教材检索构造方式。 */
    public KnowledgeRetrievalAgentService(TextbookResourceProperties textbookProperties, TextbookRetrievalService retrievalService) {
        this(textbookProperties, retrievalService, Optional.empty());
    }

    /** Runs retrieval with the backend subject in audit context; caller text never selects a tenant or data scope. */
    public KnowledgeEvidencePackResponse retrieve(KnowledgeRetrievalAgentRequest request, RequestSubject subject) {
        KnowledgeRetrievalAgentRequest normalized = request.normalize();
        RequestSubject owner = subject.normalize();
        var response = retrievalService.search(textbookProperties.processedBooksRoot(),
                new TextbookSearchRequest(normalized.query(), normalized.limit()),
                new RetrievalRequestContext(owner.tenantId(), owner.subjectType(), owner.subjectId(), null,
                        owner.deviceId(), "KnowledgeRetrievalAgent", "/api/agents/knowledge-retrieval"));
        List<KnowledgeEvidencePackResponse.Item> items = new ArrayList<>(response.hits().stream()
                .map(hit -> new KnowledgeEvidencePackResponse.Item(
                        "textbook://" + hit.docId() + "/page/" + hit.pageNo() + "#chunk=" + hit.chunkId(),
                        hit.bookName() + " / " + hit.sectionTitle(), hit.textSnippet(), hit.score()))
                .toList());
        if (canonicalMathPaperRetrievalService != null) {
            for (TeachingEvidence evidence : canonicalMathPaperRetrievalService.search(normalized.query(), normalized.limit())) {
                // 仅公开适配器已经签发的不透明证据引用；路径、collection 和存储信息不属于全局 RAG 合同。
                items.add(new KnowledgeEvidencePackResponse.Item(
                        evidence.chunkId(), evidence.sourceTitle(), evidence.snippet(), 0.0d));
            }
        }
        return new KnowledgeEvidencePackResponse("EVIDENCE_PACK", response.queryId(), response.query(), List.copyOf(items));
    }
}
