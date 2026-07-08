import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { PdfCanvasPreview } from "./components/PdfCanvasPreview";

vi.mock("pdfjs-dist", () => ({
  GlobalWorkerOptions: { workerSrc: "" },
  getDocument: vi.fn(),
}));

vi.mock("pdfjs-dist/build/pdf.worker.mjs?url", () => ({
  default: "pdf-worker-test.mjs",
}));

describe("PdfCanvasPreview", () => {
  it("renders shared PDF page controls for every preview surface", () => {
    const html = renderToStaticMarkup(
      <PdfCanvasPreview
        pdfBytes={new Uint8Array([37, 80, 68, 70])}
        pdfUrl="blob:preview-pdf"
        title="模板参考 PDF 预览"
        canvasLabel="模板 PDF 页面预览"
      />,
    );

    expect(html).toContain("模板参考 PDF 预览");
    expect(html).toContain("class=\"pdf-page-canvas\"");
    expect(html).toContain("aria-label=\"第一页\"");
    expect(html).toContain("aria-label=\"上一页\"");
    expect(html).toContain("aria-label=\"下一页\"");
    expect(html).toContain("aria-label=\"跳转到页码\"");
    expect(html).toContain("aria-label=\"最后一页\"");
    expect(html).toContain("模板 PDF 页面预览");
  });
});
