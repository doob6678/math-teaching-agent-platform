package com.doob.mathagent.student;

import com.doob.mathagent.agent.service.AiChatGateway;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.knowledge.service.KnowledgeGraphSpineService;
import com.doob.mathagent.resources.ProjectResourceProperties;
import com.doob.mathagent.resources.TextbookResourceProperties;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.student.dto.StudentExplanationRequest;
import com.doob.mathagent.student.service.StudentExplanationAiCardService;
import com.doob.mathagent.student.service.StudentExplanationHistoryStore;
import com.doob.mathagent.student.service.StudentExplanationHistorySummary;
import com.doob.mathagent.student.service.StudentExplanationImageRecord;
import com.doob.mathagent.student.service.StudentExplanationImageStoreService;
import com.doob.mathagent.student.service.StudentExplanationService;
import com.doob.mathagent.student.service.StudentExplanationVisionService;
import com.doob.mathagent.student.vo.StudentExplanationResponse;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

public final class StudentExplanationServiceFixture {

    private StudentExplanationServiceFixture() {
    }

    public static StudentExplanationService deterministic(
            TextbookResourceProperties textbookResourceProperties,
            TextbookRetrievalService textbookRetrievalService,
            KnowledgeGraphSpineService knowledgeGraphSpineService,
            TeacherResourceBlockSearchService teacherResourceBlockSearchService) {
        return service(
                textbookResourceProperties,
                textbookRetrievalService,
                knowledgeGraphSpineService,
                teacherResourceBlockSearchService,
                request -> new AiChatResult(
                        request.providerName(),
                        request.modelCode(),
                        13,
                        11,
                        24,
                        "deterministic student explanation cards",
                        """
                                {"cards":[
                                  {"cardKey":"problem_understanding","title":"Problem","summary":"Identify the vector angle task.","items":["Read the given vectors"],"sourceUris":[],"renderMode":"text"},
                                  {"cardKey":"knowledge_points","title":"Knowledge","summary":"Dot product supports angle calculation.","items":["space vector dot product"],"sourceUris":[],"renderMode":"text"},
                                  {"cardKey":"method_hint","title":"Method","summary":"Use normal vectors and dot product.","items":["Build vectors first"],"sourceUris":[],"renderMode":"text"},
                                  {"cardKey":"step_by_step","title":"Steps","summary":"Compute $a\\\\cdot b$ and compare lengths.","items":["Use $$\\\\cos\\\\theta=\\\\frac{a\\\\cdot b}{|a||b|}$$"],"sourceUris":[],"renderMode":"formula"},
                                  {"cardKey":"source_links","title":"Sources","summary":"Use backend evidence only.","items":["textbook and graph evidence"],"sourceUris":[],"renderMode":"source_list"}
                                ]}
                                """),
                deterministicProviderCatalog(),
                testImageStore(),
                testVisionService());
    }

    public static StudentExplanationService service(
            TextbookResourceProperties textbookResourceProperties,
            TextbookRetrievalService textbookRetrievalService,
            KnowledgeGraphSpineService knowledgeGraphSpineService,
            TeacherResourceBlockSearchService teacherResourceBlockSearchService,
            AiChatGateway aiChatGateway,
            AiProviderCatalog aiProviderCatalog,
            StudentExplanationImageStoreService imageStoreService,
            StudentExplanationVisionService visionService) {
        return new StudentExplanationService(
                textbookResourceProperties,
                textbookRetrievalService,
                knowledgeGraphSpineService,
                teacherResourceBlockSearchService,
                new StudentExplanationAiCardService(aiChatGateway, aiProviderCatalog),
                imageStoreService == null ? testImageStore() : imageStoreService,
                visionService == null ? testVisionService() : visionService,
                testHistoryStore());
    }

    private static StudentExplanationImageStoreService testImageStore() {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "math-agent-student-explanation-fixture");
        ProjectResourceProperties properties = new ProjectResourceProperties(
                root.resolve("test-data"),
                root.resolve("legacy-parser"),
                root.resolve("design"),
                root.resolve("reference.pdf"),
                root.resolve("prompt.pdf"),
                root.resolve("storage"));
        return new StudentExplanationImageStoreService(properties, Clock.systemUTC(), Duration.ofMinutes(30), 8_388_608);
    }

    private static AiProviderCatalog deterministicProviderCatalog() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setOpenai(new AiProviderProperties.Provider(
                "openai",
                "https://api.openai.com",
                "test-key",
                "gpt-5.4"));
        return new AiProviderCatalog(properties);
    }

    private static StudentExplanationVisionService testVisionService() {
        return new StudentExplanationVisionService(new AiProviderProperties(), "", "", false, 1000);
    }

    private static StudentExplanationHistoryStore testHistoryStore() {
        return new StudentExplanationHistoryStore() {
            @Override
            public boolean durable() {
                return false;
            }

            @Override
            public void save(
                    StudentExplanationRequest request,
                    RequestSubject subject,
                    StudentExplanationImageRecord imageRecord,
                    StudentExplanationResponse response) {
            }

            @Override
            public List<StudentExplanationHistorySummary> findRecent(
                    String tenantId,
                    String subjectType,
                    String subjectId,
                    String conversationId,
                    int limit) {
                return List.of();
            }
        };
    }
}
