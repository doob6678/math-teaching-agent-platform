# Knowledge Relation Graph Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose real `knowledge_relation` rows through backend APIs and render those relations in the frontend knowledge graph panel without invented graph edges.

**Architecture:** Reuse the existing `knowledge` controller/service/store layering. Add a read-only relation query path that resolves identity on the backend and filters edges by the visibility of both endpoint knowledge points.

**Tech Stack:** Spring Boot 3.x, Java 21, MyBatis-Plus, JUnit 5, React/Vite/Vitest.

---

### Task 1: Backend Relation Query

**Files:**
- Modify: `backend-java/src/test/java/com/doob/mathagent/knowledge/KnowledgeQuestionBankStoreTest.java`
- Modify: `backend-java/src/test/java/com/doob/mathagent/knowledge/KnowledgeQuestionBankControllerTest.java`
- Create: `backend-java/src/main/java/com/doob/mathagent/knowledge/service/KnowledgeRelationRecord.java`
- Create: `backend-java/src/main/java/com/doob/mathagent/knowledge/entity/KnowledgeRelationEntity.java`
- Create: `backend-java/src/main/java/com/doob/mathagent/knowledge/mapper/KnowledgeRelationMapper.java`
- Create: `backend-java/src/main/java/com/doob/mathagent/knowledge/vo/KnowledgeRelationResponse.java`
- Modify: `backend-java/src/main/java/com/doob/mathagent/knowledge/service/KnowledgeQuestionBankStore.java`
- Modify: `backend-java/src/main/java/com/doob/mathagent/knowledge/service/InMemoryKnowledgeQuestionBankStore.java`
- Modify: `backend-java/src/main/java/com/doob/mathagent/knowledge/service/MyBatisKnowledgeQuestionBankStore.java`
- Modify: `backend-java/src/main/java/com/doob/mathagent/knowledge/service/KnowledgeQuestionBankService.java`
- Modify: `backend-java/src/main/java/com/doob/mathagent/knowledge/controller/KnowledgeQuestionBankController.java`

- [ ] Write failing tests for visible relation filtering.
- [ ] Run focused backend tests and confirm relation methods are missing.
- [ ] Implement records, entity, mapper, store methods, service conversion, and `GET /api/knowledge/relations`.
- [ ] Run focused backend tests and confirm green.

### Task 2: Frontend API And Visualization

**Files:**
- Modify: `frontend/src/shared/api/textbookApi.ts`
- Modify: `frontend/src/shared/api/textbookApi.test.ts`
- Modify: `frontend/src/app/App.tsx`
- Modify: `frontend/src/styles.css`

- [ ] Write failing frontend API test for `/api/knowledge/relations`.
- [ ] Run focused frontend API test and confirm failure.
- [ ] Add relation type/client method and render relation rows in the knowledge bank panel.
- [ ] Run focused frontend tests and confirm green.

### Task 3: Verification And Progress Record

**Files:**
- Create: `文档/开发进度/阶段-060-知识点关系图谱查询闭环.md`

- [ ] Run backend focused tests.
- [ ] Run frontend full tests and build.
- [ ] Run `git diff --check`.
- [ ] Commit only aligned files with `feat: expose knowledge graph relations`.
