/**
 * Imports only already parsed, permission-checked teacher blocks into the real question bank.
 * It deliberately has no create-question fallback: a sparse source must remain sparse rather than be padded.
 */
import { createHash, randomUUID } from "node:crypto";

const backend = process.env.ACCEPTANCE_BACKEND_URL ?? "http://127.0.0.1:8080";
const username = process.env.ACCEPTANCE_USERNAME ?? "teacher";
const password = process.env.ACCEPTANCE_PASSWORD ?? "teacher-123456";
const documentId = process.argv[2];
if (!documentId) throw new Error("Usage: node scripts/local/import-teacher-resource-questions.mjs <documentId>");

const login = await fetch(`${backend}/api/auth/login`, {
  method: "POST", headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ username, password }),
});
if (!login.ok) throw new Error(`Login failed: ${login.status}`);
const session = await login.json();
const path = `/api/question-bank/import/teacher-resources/${encodeURIComponent(documentId)}`;
const idempotencyKey = `real-teacher-question-import-${randomUUID()}`;
const requestHash = `sha256:${createHash("sha256").update("", "utf8").digest("hex")}`;
const capabilityResponse = await fetch(`${backend}/api/security/capabilities`, {
  method: "POST",
  headers: { "Content-Type": "application/json", [session.tokenName]: session.tokenValue },
  body: JSON.stringify({ action: "question-bank:import-teacher-resource", path, requestHash, idempotencyKey, maxCost: 20 }),
});
if (!capabilityResponse.ok) throw new Error(`Capability failed: ${capabilityResponse.status} ${await capabilityResponse.text()}`);
const capability = await capabilityResponse.json();
const imported = await fetch(`${backend}${path}`, {
  method: "POST",
  headers: { [session.tokenName]: session.tokenValue, "X-Capability-Token": capability.token, "X-Request-Hash": requestHash },
});
const payload = await imported.text();
if (!imported.ok) throw new Error(`Import failed: ${imported.status} ${payload}`);
console.log(payload);
