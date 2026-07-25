import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { TeacherResourcePanel } from "./components/TeacherResourcePanel";
import { TeacherResourceDocumentResponse } from "../shared/api/textbookApi";

function resource(overrides: Partial<TeacherResourceDocumentResponse> = {}): TeacherResourceDocumentResponse {
  return {
    documentId: "doc-awaiting-index",
    tenantId: "school-a",
    ownerSubjectId: "teacher-1",
    sourceType: "feishu",
    title: "涂色问题",
    originalUrl: "https://wiki.feishu.cn/docx/coloring-problem",
    permissionScope: "TEACHER_PRIVATE",
    syncStatus: "synced",
    parseStatus: "parsed",
    embeddingStatus: "pending",
    indexStatus: "waiting_rebuild",
    feishuExportFormat: "md",
    previewFiles: [],
    parseMode: "TEXT",
    ...overrides,
  };
}

function renderPanel(value = resource()) {
  return renderToStaticMarkup(
    <TeacherResourcePanel
      resources={[value]}
      location=""
      files={[]}
      sourceType="feishu"
      scope="TEACHER_PRIVATE"
      feishuExportFormat="md"
      parseMode="TEXT"
      loading={false}
      registering={false}
      searchingBlocks={false}
      syncingResourceId=""
      importingResourceId=""
      rebuildingResourceId=""
      deletingResourceId=""
      importResult={null}
      indexRebuildResult={null}
      syncJobsByDocument={{
        [value.documentId]: [{
          jobId: "sync-1",
          documentId: value.documentId,
          operation: "sync",
          status: "completed",
          phase: "download_completed",
          message: "Downloaded and parsed resource",
        }],
      }}
      syncCheckpointsByJob={{}}
      blockSearchQuery=""
      blockSearchResult={null}
      blockSearchAudit={null}
      feishuDiscoveryQuery=""
      feishuDiscoveryResult={null}
      discoveringFeishu={false}
      error=""
      onLocationChange={vi.fn()}
      onFilesChange={vi.fn()}
      onSourceTypeChange={vi.fn()}
      onScopeChange={vi.fn()}
      onFeishuExportFormatChange={vi.fn()}
      onParseModeChange={vi.fn()}
      onBlockSearchQueryChange={vi.fn()}
      onBlockSearch={vi.fn()}
      onFeishuDiscoveryQueryChange={vi.fn()}
      onDiscoverFeishu={vi.fn()}
      onUseFeishuCandidate={vi.fn()}
      onRegister={vi.fn()}
      onArchive={vi.fn()}
      onSync={vi.fn()}
      onResume={vi.fn()}
      onImportQuestions={vi.fn()}
      onRebuildIndex={vi.fn()}
    />,
  );
}

describe("TeacherResourcePanel", () => {
  it("keeps the durable index status visible and blocks question import before readiness", () => {
    const html = renderPanel();

    expect(html).toContain("索引 待重建");
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*>.*?<span>入题库<\/span><\/button>/);
  });

  it("offers Markdown image materialization as a distinct parse mode", () => {
    const html = renderPanel();

    expect(html).toContain('value="MARKDOWN_ASSETS"');
    expect(html).toContain("Markdown：下载图片到本地");
  });
});
