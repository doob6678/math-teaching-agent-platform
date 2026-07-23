package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.KnowledgeRetrievalAgentRequest;
import com.doob.mathagent.agent.vo.KnowledgeEvidencePackResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.resources.TextbookResourceProperties;
import com.doob.mathagent.retrieval.RetrievalRequestContext;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.retrieval.TextbookSearchRequest;
import org.springframework.stereotype.Service;

/** Executes the real textbook retrieval pipeline and converts its hits into a reusable Evidence Pack artifact. */
@Service
public class KnowledgeRetrievalAgentService {
    private final TextbookResourceProperties textbookProperties;
    private final TextbookRetrievalService retrievalService;

    public KnowledgeRetrievalAgentService(TextbookResourceProperties textbookProperties, TextbookRetrievalService retrievalService) {
        this.textbookProperties = textbookProperties;
        this.retrievalService = retrievalService;
    }

    /** Runs retrieval with the backend subject in audit context; caller text never selects a tenant or data scope. */
    public KnowledgeEvidencePackResponse retrieve(KnowledgeRetrievalAgentRequest request, RequestSubject subject) {
        KnowledgeRetrievalAgentRequest normalized = request.normalize();
        RequestSubject owner = subject.normalize();
        var response = retrievalService.search(textbookProperties.processedBooksRoot(),
                new TextbookSearchRequest(normalized.query(), normalized.limit()),
                new RetrievalRequestContext(owner.tenantId(), owner.subjectType(), owner.subjectId(), null,
                        owner.deviceId(), "KnowledgeRetrievalAgent", "/api/agents/knowledge-retrieval"));
        return new KnowledgeEvidencePackResponse("EVIDENCE_PACK", response.queryId(), response.query(), response.hits().stream()
                .map(hit -> new KnowledgeEvidencePackResponse.Item(
                        "textbook://" + hit.docId() + "/page/" + hit.pageNo() + "#chunk=" + hit.chunkId(),
                        hit.bookName() + " / " + hit.sectionTitle(), hit.textSnippet(), hit.score()))
                .toList());
    }
}
