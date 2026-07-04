package com.doob.mathagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.knowledge.entity.KnowledgePointEntity;
import com.doob.mathagent.knowledge.entity.KnowledgeRelationEntity;
import com.doob.mathagent.knowledge.entity.QuestionBankItemEntity;
import com.doob.mathagent.knowledge.entity.QuestionKnowledgeLinkEntity;
import com.doob.mathagent.knowledge.mapper.KnowledgePointMapper;
import com.doob.mathagent.knowledge.mapper.KnowledgeRelationMapper;
import com.doob.mathagent.knowledge.mapper.QuestionBankItemMapper;
import com.doob.mathagent.knowledge.mapper.QuestionKnowledgeLinkMapper;
import com.doob.mathagent.knowledge.service.KnowledgePointRecord;
import com.doob.mathagent.knowledge.service.MyBatisKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.QuestionBankItemRecord;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MyBatisKnowledgeQuestionBankStoreTest {

    @Test
    void saveQuestionInsertsQuestionAndKnowledgeLinks() {
        CapturingKnowledgeMapper knowledgeMapper = new CapturingKnowledgeMapper();
        CapturingQuestionMapper questionMapper = new CapturingQuestionMapper();
        CapturingLinkMapper linkMapper = new CapturingLinkMapper();
        MyBatisKnowledgeQuestionBankStore store = new MyBatisKnowledgeQuestionBankStore(
                knowledgeMapper.proxy(),
                new CapturingRelationMapper().proxy(),
                questionMapper.proxy(),
                linkMapper.proxy());

        store.saveKnowledgePoint(new KnowledgePointRecord(
                "kp-1",
                "school-a",
                "teacher-1",
                "TEACHER_PRIVATE",
                "函数定义域",
                "函数/基础",
                "active",
                "manual"));
        store.saveQuestion(new QuestionBankItemRecord(
                "q-1",
                "school-a",
                "teacher-1",
                "TEACHER_PRIVATE",
                "函数定义域求解",
                "求函数定义域。",
                "{}",
                "easy",
                "active",
                List.of("kp-1")));

        assertThat(knowledgeMapper.inserted).hasSize(1);
        assertThat(questionMapper.inserted).hasSize(1);
        assertThat(linkMapper.inserted).hasSize(1);
        assertThat(linkMapper.inserted.getFirst().getTenantId()).isEqualTo("school-a");
        assertThat(linkMapper.inserted.getFirst().getQuestionId()).isEqualTo("q-1");
        assertThat(linkMapper.inserted.getFirst().getKnowledgePointId()).isEqualTo("kp-1");
    }

    @Test
    void searchQuestionsReloadsLinkedKnowledgePointIds() {
        CapturingQuestionMapper questionMapper = new CapturingQuestionMapper();
        QuestionBankItemEntity entity = new QuestionBankItemEntity();
        entity.setQuestionId("q-1");
        entity.setTenantId("school-a");
        entity.setOwnerSubjectId("teacher-1");
        entity.setPermissionScope("TEACHER_PRIVATE");
        entity.setQuestionTitle("函数定义域求解");
        entity.setQuestionText("求函数定义域。");
        entity.setAnswerJson("{}");
        entity.setDifficulty("easy");
        entity.setStatus("active");
        questionMapper.rows.add(entity);
        CapturingLinkMapper linkMapper = new CapturingLinkMapper();
        QuestionKnowledgeLinkEntity link = new QuestionKnowledgeLinkEntity();
        link.setTenantId("school-a");
        link.setQuestionId("q-1");
        link.setKnowledgePointId("kp-1");
        link.setStatus("active");
        linkMapper.rows.add(link);
        MyBatisKnowledgeQuestionBankStore store = new MyBatisKnowledgeQuestionBankStore(
                new CapturingKnowledgeMapper().proxy(),
                new CapturingRelationMapper().proxy(),
                questionMapper.proxy(),
                linkMapper.proxy());

        List<QuestionBankItemRecord> records = store.searchQuestions("school-a", "teacher", "teacher-1", "函数", 10);

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().knowledgePointIds()).containsExactly("kp-1");
    }

    private static final class CapturingRelationMapper {
        private final List<KnowledgeRelationEntity> inserted = new ArrayList<>();
        private final List<KnowledgeRelationEntity> rows = new ArrayList<>();

        KnowledgeRelationMapper proxy() {
            return (KnowledgeRelationMapper) Proxy.newProxyInstance(
                    KnowledgeRelationMapper.class.getClassLoader(),
                    new Class<?>[] {KnowledgeRelationMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "insert" -> {
                            inserted.add((KnowledgeRelationEntity) args[0]);
                            yield 1;
                        }
                        case "selectList" -> rows;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static final class CapturingKnowledgeMapper {
        private final List<KnowledgePointEntity> inserted = new ArrayList<>();

        KnowledgePointMapper proxy() {
            return (KnowledgePointMapper) Proxy.newProxyInstance(
                    KnowledgePointMapper.class.getClassLoader(),
                    new Class<?>[] {KnowledgePointMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "insert" -> {
                            inserted.add((KnowledgePointEntity) args[0]);
                            yield 1;
                        }
                        case "selectById" -> null;
                        case "updateById" -> 1;
                        case "selectList" -> List.of();
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static final class CapturingQuestionMapper {
        private final List<QuestionBankItemEntity> inserted = new ArrayList<>();
        private final List<QuestionBankItemEntity> rows = new ArrayList<>();

        QuestionBankItemMapper proxy() {
            return (QuestionBankItemMapper) Proxy.newProxyInstance(
                    QuestionBankItemMapper.class.getClassLoader(),
                    new Class<?>[] {QuestionBankItemMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "insert" -> {
                            inserted.add((QuestionBankItemEntity) args[0]);
                            yield 1;
                        }
                        case "selectList" -> rows;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static final class CapturingLinkMapper {
        private final List<QuestionKnowledgeLinkEntity> inserted = new ArrayList<>();
        private final List<QuestionKnowledgeLinkEntity> rows = new ArrayList<>();

        QuestionKnowledgeLinkMapper proxy() {
            return (QuestionKnowledgeLinkMapper) Proxy.newProxyInstance(
                    QuestionKnowledgeLinkMapper.class.getClassLoader(),
                    new Class<?>[] {QuestionKnowledgeLinkMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "insert" -> {
                            inserted.add((QuestionKnowledgeLinkEntity) args[0]);
                            yield 1;
                        }
                        case "selectList" -> rows;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
