# Teacher Resource Service RAG Upgrade

## Current Code Inventory

- Resource APIs already exist in `TeacherResourceController`: register, list, search, block list, sync job create/execute/resume, checkpoint read.
- Sync execution is in `TeacherSourceSyncExecutionService`. It scans `.md`, `.txt`, `.docx`, and `.pdf`, parses blocks, calls `replaceActiveBlocks`, and then rebuilds only the current document vectors through `VectorIndexService`.
- Production sync wiring is pinned in `TeacherSourceSyncExecutionConfiguration`. Do not re-enable component scanning on `TeacherSourceSyncExecutionService` while the shorter test constructors exist, because they intentionally use disabled asset persistence.
- Parsed blocks are stored in `document_block`. The table already has `source_path`, `block_order`, `chapter`, `section`, `page_no`, `image_refs`, `graph_node_ids_json`, `graph_tag_names_json`, and `checksum`.
- Feishu download already runs through `TeacherFeishuDownloadClient` and checkpoint rows. The downloader keeps cursor/downloaded/failed item JSON, including manifest rows with `relativePath`, `checksum`, `mimeType`, `sizeBytes`, and `assetKind`, so resume remains provider-state based and Java can persist native image/attachment assets.
- Two-stage retrieval remains in `TeacherResourceBlockSearchService`: document-level candidate filtering/recall first, then in-document block rerank with source path, role, order, graph tags, and neighbor evidence.
- MCP Streamable HTTP is not changed by this step. Asset reads use backend `RequestSubject` resolution and should be exposed to MCP tools only through the same permission gate.

## Implemented In This Step

- Added `teacher_resource_asset` for extracted PDF/DOCX/Feishu images and attachments, with tenant, owner, document, optional block, permission scope, source path, page, provider asset id, checksum, mime type, size, storage key, and active/inactive status.
- Added `source_document.parse_mode` and metadata fallback. `TEXT` is deterministic text/structure extraction. `AI` is recorded as the requested paid semantic mode, but current backend explicitly falls back to TEXT when no real AI labeling client is configured.
- Added `TeacherResourceAssetService` and MyBatis store. Asset binaries are written under a backend-owned storage root and returned only through `GET /api/teacher/resources/assets/{assetId}` after subject permission checks.
- DOCX parsing now extracts embedded images from runs and stores them as assets. If POI exposes a drawing relationship but returns an empty image stream, the parser falls back to the real `word/media/*` package entry; this is required for python-docx and similar producer output. PDF parsing now stores page-level PNG screenshots as assets. `imageRefs` stores opaque `assetId` references, not local paths or provider tokens.
- Feishu manifest ingestion now persists downloaded rows marked `assetKind=image` or `assetKind=attachment` into `teacher_resource_asset`. Exported documents remain parse inputs and are not double-counted as assets.
- Sync invalidates active assets for the same document before re-parsing, matching the existing block/vector incremental update contract.
- Frontend resource registration now exposes only two parse modes: `TEXT` and `AI`. OCR is not a separate user mode.

## Permission Model

- Admin can read tenant assets.
- Teacher can read owned private assets and shared/public scopes visible in the same tenant.
- Non-teacher/admin subjects cannot read teacher-resource assets through this endpoint.
- The endpoint returns 404 for invisible assets to avoid private asset id probing.
- Storage keys remain server-relative and are never returned directly.

## Verified Runtime Evidence

- Local service status used real MySQL/Redis/Milvus through WSL proxy. Flyway schema version is 18 and Milvus collection is `math_agent_resource_blocks`.
- DOCX image smoke used `output/smoke/teacher-resource-assets/asset_smoke_docxlib.docx`, which contains `word/media/image1.png`.
- Real backend register/sync produced document `2074840927429881858`, parsed 3 blocks, rebuilt only that document's vectors, and inserted asset `6b78231b-4953-4c61-a772-9b9021842487`.
- MySQL evidence: block order 2 has `image_refs` containing the asset id, and `teacher_resource_asset` has one active `image/png` row with width 80, height 40, provider asset id `/word/media/image1.png`, and server storage key `school-a/2074840927429881858/6b78231b-4953-4c61-a772-9b9021842487.png`.
- Controlled read evidence: `GET /api/teacher/resources/assets/6b78231b-4953-4c61-a772-9b9021842487` with admin login returned HTTP 200, `Content-Type: image/png`, 296 bytes, without exposing the storage path.
- Verification commands run: `python -m py_compile ai-worker-python/scripts/download_feishu_url.py`, `mvn -q -DskipTests compile`, `mvn -q "-Dtest=TeacherSourceSyncExecutionServiceTest" test`, and `mvn -q "-Dtest=TeacherResourceServiceTest,TeacherResourceControllerTest,TeacherSourceSyncExecutionServiceTest,VectorIndexServiceTest" test`.

## Remaining Work

- AI compact labeling is not implemented yet. Current behavior records `AI` parse mode and honestly reports fallback to TEXT extraction instead of faking AI success.
- `blockRole` is still a low-confidence parser-side signal. It should be further separated into explicit `roleConfidence`, `shortTitle`, and `brief` fields before relying on it in production ranking diagnostics.
- The full frontend resource workbench still needs upload/folder UX, sync phase detail, and asset preview polish. Current frontend has parse mode selection but not the complete operator dashboard.
