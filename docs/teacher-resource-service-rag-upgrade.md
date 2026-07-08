# Teacher Resource Service RAG Upgrade

## Current Code Inventory

- Resource APIs already exist in `TeacherResourceController`: register, list, search, block list, sync job create/execute/resume, checkpoint read.
- Sync execution is in `TeacherSourceSyncExecutionService`. It scans `.md`, `.txt`, `.docx`, and `.pdf`, parses blocks, calls `replaceActiveBlocks`, and then rebuilds only the current document vectors through `VectorIndexService`.
- Parsed blocks are stored in `document_block`. The table already has `source_path`, `block_order`, `chapter`, `section`, `page_no`, `image_refs`, `graph_node_ids_json`, `graph_tag_names_json`, and `checksum`.
- Feishu download already runs through `TeacherFeishuDownloadClient` and checkpoint rows. The downloader keeps cursor/downloaded/failed item JSON, so resume remains provider-state based.
- Two-stage retrieval remains in `TeacherResourceBlockSearchService`: document-level candidate filtering/recall first, then in-document block rerank with source path, role, order, graph tags, and neighbor evidence.
- MCP Streamable HTTP is not changed by this step. Asset reads use backend `RequestSubject` resolution and should be exposed to MCP tools only through the same permission gate.

## Implemented In This Step

- Added `teacher_resource_asset` for extracted PDF/DOCX/Feishu images and attachments, with tenant, owner, document, optional block, permission scope, source path, page, provider asset id, checksum, mime type, size, storage key, and active/inactive status.
- Added `source_document.parse_mode` and metadata fallback. `TEXT` is deterministic text/structure extraction. `AI` is recorded as the requested paid semantic mode, but current backend explicitly falls back to TEXT when no real AI labeling client is configured.
- Added `TeacherResourceAssetService` and MyBatis store. Asset binaries are written under a backend-owned storage root and returned only through `GET /api/teacher/resources/assets/{assetId}` after subject permission checks.
- DOCX parsing now extracts embedded images from runs and stores them as assets. PDF parsing now stores page-level PNG screenshots as assets. `imageRefs` stores opaque `assetId` references, not local paths or provider tokens.
- Sync invalidates active assets for the same document before re-parsing, matching the existing block/vector incremental update contract.
- Frontend resource registration now exposes only two parse modes: `TEXT` and `AI`. OCR is not a separate user mode.

## Permission Model

- Admin can read tenant assets.
- Teacher can read owned private assets and shared/public scopes visible in the same tenant.
- Non-teacher/admin subjects cannot read teacher-resource assets through this endpoint.
- The endpoint returns 404 for invisible assets to avoid private asset id probing.
- Storage keys remain server-relative and are never returned directly.

## Remaining Work

- Feishu image/attachment manifest ingestion still needs downloader output contract support. The Java asset table is ready, but the Python downloader must emit stable image/attachment manifest rows before Java can persist Feishu-native assets.
- AI compact labeling is not implemented yet. Current behavior records `AI` parse mode and honestly reports fallback to TEXT extraction instead of faking AI success.
- `blockRole` is still a low-confidence parser-side signal. It should be further separated into explicit `roleConfidence`, `shortTitle`, and `brief` fields before relying on it in production ranking diagnostics.
- A real database smoke run with MySQL/Redis/Milvus should be executed after applying `V18`, using an actual DOCX/PDF containing images, to verify rows, asset streaming, and per-document vector rebuild together.
