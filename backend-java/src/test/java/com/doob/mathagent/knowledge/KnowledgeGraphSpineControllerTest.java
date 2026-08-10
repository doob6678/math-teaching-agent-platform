package com.doob.mathagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.knowledge.controller.KnowledgeGraphSpineController;
import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgeGraphSpineProperties;
import com.doob.mathagent.knowledge.service.KnowledgeGraphSpineSeedService;
import com.doob.mathagent.knowledge.service.KnowledgeGraphSpineService;
import com.doob.mathagent.knowledge.vo.KnowledgeGraphSpineResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class KnowledgeGraphSpineControllerTest {

    @Test
    void studentReadsCuratedGraphUsingBackendResolvedIdentity() {
        InMemoryKnowledgeQuestionBankStore store = new InMemoryKnowledgeQuestionBankStore();
        new KnowledgeGraphSpineSeedService(store, new KnowledgeGraphSpineProperties())
                .seedFromConfiguredSource();
        KnowledgeGraphSpineController controller = new KnowledgeGraphSpineController(
                new KnowledgeGraphSpineService(store),
                // The seed source is intentionally tenant-scoped to the configured default tenant; the controller
                // must still take role and subject identity from this resolver rather than the spoofed header.
                request -> new RequestSubject("default", "student", "student-1", "device-1"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Subject-Id", "teacher-spoofed");

        KnowledgeGraphSpineResponse response = controller.displaySpine(request);

        assertThat(response.viewerRole()).isEqualTo("student");
        // The curated source now includes the configured method-node budget; keep the controller contract aligned
        // with KnowledgeGraphSpineSeedServiceTest so the identity boundary is tested against the complete graph.
        assertThat(response.nodeCount()).isEqualTo(141);
        assertThat(response.edgeCount()).isEqualTo(139);
        assertThat(response.nodes()).extracting(KnowledgeGraphSpineResponse.Node::label)
                .contains(
                        "\u51fd\u6570",
                        "\u5bfc\u6570\u7814\u7a76\u51fd\u6570",
                        "\u7a7a\u95f4\u5411\u91cf");
    }
}
