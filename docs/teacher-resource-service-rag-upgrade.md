# Teacher Resource Service RAG Upgrade

## Current Code Inventory

- Resource APIs already exist in `TeacherResourceController`: register, list, search, block list, sync job create/execute/resume, checkpoint read.
- Sync execution is in `TeacherSourceSyncExecutionService`. It scans `.md`, `.txt`, `.docx`, and `.pdf`, parses blocks, calls `replaceActiveBlocks`, and then rebuilds only the current document vectors through `VectorIndexService`.
- Production sync wiring is pinned in `TeacherSourceSyncExecutionConfiguration`. Do not re-enable component scanning on `TeacherSourceSyncExecutionService` while the shorter test constructors exist, because they intentionally use disabled asset persistence.
- Parsed blocks are stored in `document_block`. The table already has `source_path`, `block_order`, `chapter`, `section`, `page_no`, `image_refs`, `graph_node_ids_json`, `graph_tag_names_json`, and `checksum`.
- Feishu download already runs through `TeacherFeishuDownloadClient` and checkpoint rows. The downloader keeps cursor/downloaded/failed item JSON, including manifest rows with `relativePath`, `checksum`, `mimeType`, `sizeBytes`, and `assetKind`, so resume remains provider-state based and Java can persist native image/attachment assets.
- Two-stage retrieval remains in `TeacherResourceBlockSearchService`: document-level candidate filtering/recall first, then in-document block rerank with source path, role, order, graph tags, and neighbor evidence.
- Public textbook processed assets already include `_page_image_index/manifest.json`, `metadata.jsonl`, and `page_embeddings.npy`, which can be reused for CLIP page-image search without rebuilding a parallel index inside Java.
- MCP Streamable HTTP is not changed by this step. Asset reads use backend `RequestSubject` resolution and should be exposed to MCP tools only through the same permission gate.

## Implemented In This Step

- Added `teacher_resource_asset` for extracted PDF/DOCX/Feishu images and attachments, with tenant, owner, document, optional block, permission scope, source path, page, provider asset id, checksum, mime type, size, storage key, and active/inactive status.
- Added `source_document.parse_mode` and metadata fallback. `TEXT` is deterministic text/structure extraction. `AI` is recorded as the requested paid semantic mode, but current backend explicitly falls back to TEXT when no real AI labeling client is configured.
- Added backend-managed teacher upload staging:
  - `POST /api/teacher/resources/upload` accepts multipart files from teacher/admin sessions, including browser folder uploads and ZIP packages.
  - Uploads are stored under the existing local file storage root in a tenant/subject-owned directory, then immediately registered as a normal `local_path` teacher resource.
  - This reuses the current sync-job, parser, question-bank import, and vector rebuild pipeline instead of creating a second ingestion path just for uploads.
- Added `TeacherResourceAssetService` and MyBatis store. Asset binaries are written under a backend-owned storage root and returned only through `GET /api/teacher/resources/assets/{assetId}` after subject permission checks.
- Teacher resource search hits now surface extracted asset metadata:
  - `TeacherResourceBlockSearchResponse.Hit` carries `imageAssetIds` and resolved `assetRefs`.
  - `assetRefs` expose only backend-controlled URLs like `/api/teacher/resources/assets/{assetId}` plus MIME/source metadata; no local path, Feishu token, or storage key is leaked.
  - `search_teacher_resource_evidence` and mixed MCP merged rows forward the same asset references so AI/tool callers can cite or display the matching image without bypassing backend authorization.
- DOCX parsing now extracts embedded images from runs and stores them as assets. If POI exposes a drawing relationship but returns an empty image stream, the parser falls back to the real `word/media/*` package entry; this is required for python-docx and similar producer output. PDF parsing now stores page-level PNG screenshots as assets. `imageRefs` stores opaque `assetId` references, not local paths or provider tokens.
- Feishu manifest ingestion now persists downloaded rows marked `assetKind=image` or `assetKind=attachment` into `teacher_resource_asset`. Exported documents remain parse inputs and are not double-counted as assets.
- Sync invalidates active assets for the same document before re-parsing, matching the existing block/vector incremental update contract.
- Frontend resource registration now exposes only two parse modes: `TEXT` and `AI`. OCR is not a separate user mode.
- Frontend teacher resource workbench now uses the real upload path instead of asking operators to manually pre-stage files:
  - non-Feishu resources can be uploaded as files, browser folders, or ZIPs through `uploadTeacherResource(...)`
  - uploaded files preserve browser folder-relative paths through multipart filenames so backend staging can rebuild the same tree before sync
  - operators can choose logical source types such as `teacher_resource`, `qq_bundle`, `gaokao`, and `mock_exam` instead of collapsing everything into `local_path`
  - teacher search hits now render backend-controlled `assetRefs` so the operator can open matched page screenshots or embedded images without exposing storage paths
- Added public textbook page-image support on both sides:
  - Worker endpoint `POST /v1/clip/page-search` reuses the local `_page_image_index` under `processed_books` and supports text or image CLIP queries.
  - Backend endpoint `POST /api/retrieval/textbooks/page-search` proxies to the worker with the existing worker API key, repairs common mojibake in worker metadata, and rewrites page hits to backend-owned image URLs.
  - Backend endpoint `GET /api/resources/textbooks/{docId}/pages/{pageNo}/image` streams a processed textbook page image without exposing `processed_books` absolute paths.
  - Standard `TextbookRetrievalService` hits now also carry `pageImageUri`, and MCP merged textbook rows forward the same controlled URI. This keeps the normal two-stage textbook/tool search path aligned with the new page-image capability instead of forcing callers onto a separate endpoint first.
- CLIP page-image search intentionally stays out of the JVM model path. Java only validates requests, calls the worker, and maps doc/page hits back to controlled URLs. This avoids reloading numpy indexes or CLIP weights in the backend process.

## Permission Model

- Admin can read tenant assets.
- Teacher can read owned private assets and shared/public scopes visible in the same tenant.
- Non-teacher/admin subjects cannot read teacher-resource assets through this endpoint.
- The endpoint returns 404 for invisible assets to avoid private asset id probing.
- Storage keys remain server-relative and are never returned directly.
- Public textbook page images are treated as controlled public resources. The URL is public, but the local file path remains backend-only and is resolved by docId/pageNo through the page-image index.

## Verified Runtime Evidence

- Local service status uses real MySQL/Redis/Milvus through the WSL proxy. Database tables are managed outside application startup; no migration runner is bundled.
- DOCX image smoke used `output/smoke/teacher-resource-assets/asset_smoke_docxlib.docx`, which contains `word/media/image1.png`.
- Real backend register/sync produced document `2074840927429881858`, parsed 3 blocks, rebuilt only that document's vectors, and inserted asset `6b78231b-4953-4c61-a772-9b9021842487`.
- MySQL evidence: block order 2 has `image_refs` containing the asset id, and `teacher_resource_asset` has one active `image/png` row with width 80, height 40, provider asset id `/word/media/image1.png`, and server storage key `school-a/2074840927429881858/6b78231b-4953-4c61-a772-9b9021842487.png`.
- Controlled read evidence: `GET /api/teacher/resources/assets/6b78231b-4953-4c61-a772-9b9021842487` with admin login returned HTTP 200, `Content-Type: image/png`, 296 bytes, without exposing the storage path.
- Worker-side CLIP page-image smoke:
  - Reused real `processed_books/_page_image_index` with 1118 rows.
  - Reused real local CLIP model `D:\ModelScope\models\damo\multi-modal_clip-vit-large-patch14_zh`.
  - Added compatibility for historical page-image indexes whose stored dimension differs from the current query-side CLIP export dimension, so existing 768-dim indexes remain searchable from the current 512-dim worker path.
- Java-side CLIP page-image smoke:
  - Compiled the new textbook page-image classes separately against the current `target/classes` and dependency classpath because unrelated teaching worktree changes still block full-module Maven compilation.
  - Real smoke invoked `TextbookPageImageService.openPageImage(...)` on `renjiao_bbixiu1math` page 149 and confirmed the backend-resolved `image/png` resource exists.
  - Real smoke invoked `TextbookPageImageSearchService.search(...)` against a temporary worker on `http://127.0.0.1:18091/v1`, got 3 real hits for query `函数单调性`, and rewrote the top hit to `/api/resources/textbooks/renjiao_bbixiu1math/pages/3/image`.
- Verification commands run: `python -m py_compile ai-worker-python/scripts/download_feishu_url.py`, `mvn -q -DskipTests compile`, `mvn -q "-Dtest=TeacherSourceSyncExecutionServiceTest" test`, and `mvn -q "-Dtest=TeacherResourceServiceTest,TeacherResourceControllerTest,TeacherSourceSyncExecutionServiceTest,VectorIndexServiceTest" test`.
- Upload staging verification:
  - `TeacherResourceUploadServiceTest` verifies owner-scoped folder-style multipart path preservation and ZIP expansion.
  - `TeacherResourceControllerTest.uploadEndpointStoresFilesRegistersLocalResourceAndSyncsThroughExistingPipeline` verifies uploaded Markdown files can be registered, queued, parsed, and listed through the existing teacher resource controller flow.
- Search asset reference verification:
  - `TeacherResourceControllerTest.searchReturnsVisibleAssetRefsForTeacherOwnedBlocks` verifies visible teacher search hits include `imageAssetIds` and backend asset URLs.
  - `McpToolExecutionServiceTest.teacherMcpSecretCallsTeacherResourceEvidenceWithoutLeakingOtherTeacherPrivateBlocks` now also verifies MCP teacher-resource evidence returns the same controlled asset reference.
  - Direct JShell smoke confirmed a parsed block search result returned `ASSET_IDS=[...]`, `ASSET_REFS=1`, and `ASSET_URI=/api/teacher/resources/assets/{assetId}`.
- Frontend verification:
  - `frontend`: `npm test -- src/shared/api/textbookApi.test.ts`
  - `frontend`: `npm run build`
  - The API test now covers multipart teacher upload capability flow, verifies there are no client-supplied identity headers, and confirms the browser folder-relative filename is preserved in the request contract.

## Remaining Work

- AI compact labeling is not implemented yet. Current behavior records `AI` parse mode and honestly reports fallback to TEXT extraction instead of faking AI success.
- `blockRole` is still a low-confidence parser-side signal. It should be further separated into explicit `roleConfidence`, `shortTitle`, and `brief` fields before relying on it in production ranking diagnostics.
- The new textbook page-image search is currently a backend/worker slice only. It still needs frontend affordances and, if desired later, MCP tool exposure through the same backend-controlled page image URL contract.
- The frontend still lacks richer sync phase timelines and inline image preview components; it now has the real upload/folder UX and protected asset links, but not a full operator dashboard for progress drill-down.
