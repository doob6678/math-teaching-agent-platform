# Agent Tool Preferences Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add backend-enforced user tool preferences so an agent run can disable specific tools for one request without trusting frontend identity or broadening agent policy.

**Architecture:** Extend `AgentRunPlanRequest` with `disabledToolScopes`, keep role/agent allow lists in `AgentRunPolicy`, and make `AgentRunPlanService` compute effective tools as requested scopes minus backend-disallowed scopes minus user-disabled scopes. Return a structured `toolPolicyDecisions` list so the UI and audit layer can explain whether a tool was allowed, denied by policy, or disabled by user preference.

**Tech Stack:** Java 21 records, Spring Boot service layer, Vitest TypeScript API tests, existing local git stage commits.

---

### Task 1: Backend RED Tests

**Files:**
- Modify: `backend-java/src/test/java/com/doob/mathagent/agent/AgentRunPlanServiceTest.java`

- [ ] **Step 1: Write failing tests**

Add tests requiring:
- `disabledToolScopes` removes an otherwise allowed tool from `allowedToolScopes`.
- `toolPolicyDecisions` records `DISABLED_BY_USER` and `DENIED_BY_AGENT_POLICY`.

- [ ] **Step 2: Run RED**

Run: `mvn "-Dtest=AgentRunPlanServiceTest" test`

Expected: compile failure because `AgentRunPlanRequest` and `AgentRunPlanResponse` do not yet expose the new fields.

### Task 2: Backend Implementation

**Files:**
- Modify: `backend-java/src/main/java/com/doob/mathagent/agent/dto/AgentRunPlanRequest.java`
- Modify: `backend-java/src/main/java/com/doob/mathagent/agent/vo/AgentRunPlanResponse.java`
- Modify: `backend-java/src/main/java/com/doob/mathagent/agent/service/AgentRunPlanService.java`
- Modify: `backend-java/src/main/java/com/doob/mathagent/agent/service/AgentRunExecutionService.java`
- Modify existing tests that instantiate `AgentRunPlanResponse` directly.

- [ ] **Step 1: Add request field**

Add `disabledToolScopes` to `AgentRunPlanRequest`, normalize it with the same null-safe list logic, and document that it is a per-request user preference, not an authorization input.

- [ ] **Step 2: Add response decisions**

Add `List<ToolPolicyDecision> toolPolicyDecisions` to `AgentRunPlanResponse`, with fields `scope`, `decision`, and `reason`.

- [ ] **Step 3: Apply backend policy**

In `AgentRunPlanService`, compute:
- allowed: requested scope is in agent allow list and not disabled.
- denied: requested scope is not in agent allow list or is disabled.
- decisions: one row per requested scope.

- [ ] **Step 4: Preserve execution safety**

In `AgentRunExecutionService`, reject a frontend-returned plan if `allowedToolScopes` contains a scope that has a `DISABLED_BY_USER` decision.

- [ ] **Step 5: Run GREEN**

Run: `mvn "-Dtest=AgentRunPlanServiceTest,AgentRunExecutionServiceTest" test`

Expected: all targeted backend tests pass.

### Task 3: Frontend API RED/GREEN

**Files:**
- Modify: `frontend/src/shared/api/textbookApi.test.ts`
- Modify: `frontend/src/shared/api/textbookApi.ts`

- [ ] **Step 1: Write failing API test**

Add a test that `planAgentRun` sends `disabledToolScopes` and can read `toolPolicyDecisions`.

- [ ] **Step 2: Run RED**

Run: `npm test`

Expected: TypeScript failure because the API types do not yet define the new fields.

- [ ] **Step 3: Update API types**

Add `disabledToolScopes` to `AgentRunPlanRequest` and `toolPolicyDecisions` to `AgentRunPlanResponse`.

- [ ] **Step 4: Run GREEN**

Run: `npm test`

Expected: frontend API tests pass.

### Task 4: Documentation and Verification

**Files:**
- Create: `文档/开发进度/阶段-035-Agent工具偏好与动态注入裁剪.md`

- [ ] **Step 1: Write stage document**

Record goal, backend changes, frontend API changes, permission boundary, TDD evidence, and verification commands.

- [ ] **Step 2: Full verification**

Run:
- `mvn test`
- `python -m pytest tests`
- `npm test`
- `npm run build`
- `rg -n "(AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|sk-[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|BEGIN (RSA|OPENSSH|EC|DSA) PRIVATE KEY)" .`
- `git diff --check`

- [ ] **Step 3: Commit**

Stage only stage 035 files and commit:

```bash
git commit -m "feat: add agent tool preference policy"
```
