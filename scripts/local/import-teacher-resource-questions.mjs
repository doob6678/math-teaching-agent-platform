/**
 * Imports only already parsed, permission-checked teacher blocks into the real question bank.
 * It deliberately has no create-question fallback: a sparse source must remain sparse rather than be padded.
 */
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
const imported = await fetch(`${backend}${path}`, {
  method: "POST",
  headers: { [session.tokenName]: session.tokenValue },
});
const payload = await imported.text();
if (!imported.ok) throw new Error(`Import failed: ${imported.status} ${payload}`);
console.log(payload);
