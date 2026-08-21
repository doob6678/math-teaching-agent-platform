package com.doob.mathagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgeGraphSpineProperties;
import com.doob.mathagent.knowledge.service.KnowledgeGraphSpineSeedService;
import com.doob.mathagent.knowledge.service.KnowledgeGraphSpineService;
import com.doob.mathagent.knowledge.vo.KnowledgeGraphSpineResponse;
import com.doob.mathagent.teacher.search.TeacherResourceGraphAlignmentService;
import org.junit.jupiter.api.Test;

class KnowledgeGraphSpineSeedServiceTest {

    @Test
    void seedsCuratedDisplaySpineFromUtf8MarkdownSource() {
        InMemoryKnowledgeQuestionBankStore store = new InMemoryKnowledgeQuestionBankStore();
        KnowledgeGraphSpineProperties properties = new KnowledgeGraphSpineProperties();
        KnowledgeGraphSpineSeedService seedService = new KnowledgeGraphSpineSeedService(store, properties);

        KnowledgeGraphSpineSeedService.KnowledgeGraphSpineSeedResult result =
                seedService.seedFromConfiguredSource();
        KnowledgeGraphSpineResponse graph = new KnowledgeGraphSpineService(store)
                .displaySpine("default", "student", "student-1");

        assertThat(result.executed()).isTrue();
        assertThat(properties.getTenantId()).isEqualTo("default");
        assertThat(properties.getMethodNodeLimit()).isEqualTo(120);
        assertThat(result.nodeCount()).isEqualTo(142);
        assertThat(result.relationCount()).isEqualTo(146);
        assertThat(graph.nodeCount()).isEqualTo(142);
        assertThat(graph.edgeCount()).isEqualTo(146);
        assertThat(graph.nodes()).extracting(KnowledgeGraphSpineResponse.Node::label)
                .contains(
                        "\u51fd\u6570",
                        "\u5bfc\u6570",
                        "\u51fd\u6570\u6982\u5ff5\u4e0e\u8868\u793a",
                        "\u7a7a\u95f4\u5411\u91cf",
                        "\u5b9a\u4e49\u57df\u6c42\u89e3",
                        "\u7ebf\u9762\u89d2");
        assertThat(graph.nodes()).extracting(KnowledgeGraphSpineResponse.Node::nodeType)
                .contains("MODULE", "TOPIC", "METHOD");
        assertThat(graph.edges()).extracting(KnowledgeGraphSpineResponse.Edge::relationType)
                .contains("CONTAINS_TOPIC", "METHOD_FOR", "PREREQUISITE_FOR", "RELATED_TO", "COMMON_MISTAKE_FOR");
        assertThat(graph.nodes().toString()).doesNotContain("OCR", "page/formula/topic");
        assertThat(graph.nodes().stream()
                .filter(node -> node.label().equals("函数概念与表示"))
                .findFirst()
                .orElseThrow()
                .sourceSummary())
                .contains("routingAliases=函数基础", "retrievalSignals=定义域");
        assertThat(graph.nodes()).extracting(KnowledgeGraphSpineResponse.Node::label)
                .doesNotContain("第一版规模控制", "版本范围");
    }

    @Test
    void seedIsIdempotentBecauseNodeAndRelationIdsAreDeterministic() {
        InMemoryKnowledgeQuestionBankStore store = new InMemoryKnowledgeQuestionBankStore();
        KnowledgeGraphSpineSeedService seedService =
                new KnowledgeGraphSpineSeedService(store, new KnowledgeGraphSpineProperties());

        seedService.seedFromConfiguredSource();
        seedService.seedFromConfiguredSource();
        KnowledgeGraphSpineResponse graph = new KnowledgeGraphSpineService(store)
                .displaySpine("default", "teacher", "teacher-1");

        assertThat(graph.nodeCount()).isEqualTo(142);
        assertThat(graph.edgeCount()).isEqualTo(146);
        assertThat(new KnowledgeGraphSpineService(store)
                .displaySpine("school-a", "teacher", "teacher-1")
                .nodeCount()).isZero();
    }

    @Test
    void routesFeishuTerminologyFromTheVersionedGraphInsteadOfJavaAliases() {
        InMemoryKnowledgeQuestionBankStore store = new InMemoryKnowledgeQuestionBankStore();
        new KnowledgeGraphSpineSeedService(store, new KnowledgeGraphSpineProperties())
                .seedFromConfiguredSource();

        TeacherResourceGraphAlignmentService.QueryGraphContext context =
                new TeacherResourceGraphAlignmentService(store).alignQuery(
                        "default", "teacher", "teacher-1", "请查找函数基础里关于定义域的讲义");

        assertThat(context.primaryTagNames()).contains("函数概念与表示");
        assertThat(context.expandedTagNames()).contains("函数", "函数概念与表示");
    }
}
