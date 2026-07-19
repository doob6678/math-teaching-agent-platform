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
                request -> new RequestSubject("school-a", "student", "student-1", "device-1"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Subject-Id", "teacher-spoofed");

        KnowledgeGraphSpineResponse response = controller.displaySpine(request);

        assertThat(response.viewerRole()).isEqualTo("student");
        assertThat(response.nodeCount()).isEqualTo(84);
        assertThat(response.edgeCount()).isEqualTo(83);
        assertThat(response.nodes()).extracting(KnowledgeGraphSpineResponse.Node::label)
                .contains(
                        "\u51fd\u6570",
                        "\u5bfc\u6570\u7814\u7a76\u51fd\u6570",
                        "\u7a7a\u95f4\u5411\u91cf");
    }
}
