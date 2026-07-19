import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { SyncCheckpointView } from "./App";
import { TeacherSourceSyncCheckpointResponse } from "../shared/api/textbookApi";

describe("SyncCheckpointView", () => {
  it("renders downloaded and failed Feishu checkpoint details", () => {
    const checkpoint: TeacherSourceSyncCheckpointResponse = {
      jobId: "job-1",
      tenantId: "school-a",
      documentId: "doc-1",
      rootToken: "root-token",
      currentFolderToken: "folder-token",
      currentPath: "高中数学/概率统计",
      pageToken: "page-token",
      visitedFolderTokensJson: "[\"root-token\",\"folder-token\"]",
      downloadedItemsJson: JSON.stringify([
        {
          type: "docx",
          token: "DULWdiJKgoL6hLxwiMCcTRBrnOc",
          name: "期望和方差的性质",
          path: "高中数学/概率统计/期望和方差的性质",
        },
      ]),
      failedItemsJson: JSON.stringify([
        {
          message: "ProxyError: tunnel connection reset",
          retryable: true,
        },
      ]),
      cursorVersion: 2,
      updatedAt: "2026-07-01T04:00:00Z",
    };

    const html = renderToStaticMarkup(<SyncCheckpointView checkpoint={checkpoint} />);

    expect(html).toContain("1 已下载");
    expect(html).toContain("1 失败");
    expect(html).toContain("高中数学/概率统计");
    expect(html).toContain("有游标");
    expect(html).not.toContain("folder-token");
    expect(html).not.toContain("DULWdiJKgoL6hLxwiMCcTRBrnOc");
  });
});
