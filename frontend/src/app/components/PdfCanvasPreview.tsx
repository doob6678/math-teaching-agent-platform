import { useEffect, useRef, useState } from "react";
import { ArrowLeft, ArrowRight, ExternalLink, FileText, Loader2 } from "lucide-react";
import * as pdfjsLib from "pdfjs-dist";
import pdfWorkerUrl from "pdfjs-dist/build/pdf.worker.mjs?url";

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfWorkerUrl;

export type PdfPreviewMeta = {
  renderer?: string;
  pageCount?: number;
};

export function PdfCanvasPreview({
  pdfBytes,
  pdfUrl,
  meta = null,
  title = "PDF 真实渲染预览",
  canvasLabel = "讲义 PDF 页面预览",
}: {
  pdfBytes: Uint8Array | null;
  pdfUrl: string;
  meta?: PdfPreviewMeta | null;
  title?: string;
  canvasLabel?: string;
}) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [state, setState] = useState<"loading" | "ready" | "failed">("loading");
  const [pageInfo, setPageInfo] = useState("");
  const [pageCount, setPageCount] = useState(0);
  const [currentPage, setCurrentPage] = useState(1);
  const [pageInput, setPageInput] = useState("1");

  useEffect(() => {
    setCurrentPage(1);
    setPageCount(0);
    setPageInput("1");
  }, [pdfBytes]);

  useEffect(() => {
    setPageInput(String(currentPage));
  }, [currentPage]);

  useEffect(() => {
    let cancelled = false;
    async function renderPdf() {
      const canvas = canvasRef.current;
      if (!canvas || !pdfBytes?.byteLength) {
        setState(pdfBytes?.byteLength ? "loading" : "failed");
        return;
      }
      setState("loading");
      try {
        const loadingTask = pdfjsLib.getDocument({ data: pdfBytes.slice() });
        const pdf = await loadingTask.promise;
        const totalPages = pdf.numPages;
        const safePage = Math.min(Math.max(1, currentPage), totalPages);
        if (safePage !== currentPage) {
          setCurrentPage(safePage);
          await pdf.cleanup();
          return;
        }
        const page = await pdf.getPage(safePage);
        const baseViewport = page.getViewport({ scale: 1 });
        const containerWidth = Math.min(880, canvas.parentElement?.clientWidth ?? 880);
        const scale = Math.max(1, Math.min(1.6, containerWidth / baseViewport.width));
        const viewport = page.getViewport({ scale });
        const pixelRatio = window.devicePixelRatio || 1;
        canvas.width = Math.floor(viewport.width * pixelRatio);
        canvas.height = Math.floor(viewport.height * pixelRatio);
        canvas.style.width = `${Math.floor(viewport.width)}px`;
        canvas.style.height = `${Math.floor(viewport.height)}px`;
        const context = canvas.getContext("2d");
        if (!context) {
          throw new Error("canvas context unavailable");
        }
        context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
        await page.render({ canvas, canvasContext: context, viewport }).promise;
        await pdf.cleanup();
        if (!cancelled) {
          setPageCount(totalPages);
          setPageInfo(`第 ${safePage} 页 / 共 ${totalPages} 页`);
          setState("ready");
        }
      } catch {
        if (!cancelled) {
          setState("failed");
        }
      }
    }
    renderPdf();
    return () => {
      cancelled = true;
    };
  }, [pdfBytes, currentPage]);

  function commitPageInput() {
    const parsed = Number.parseInt(pageInput, 10);
    if (!Number.isFinite(parsed)) {
      setPageInput(String(currentPage));
      return;
    }
    const safePage = Math.min(Math.max(1, parsed), pageCount || 1);
    setCurrentPage(safePage);
    setPageInput(String(safePage));
  }

  const totalPages = pageCount || meta?.pageCount || 0;

  return (
    <div className="pdf-canvas-preview" data-preview-state={state} data-page-count={totalPages}>
      <div className="pdf-canvas-toolbar">
        <div className="pdf-canvas-summary">
          <strong>{title}</strong>
          <span>{state === "ready" ? pageInfo : state === "loading" ? "正在渲染页面" : "Canvas 预览不可用"}</span>
          {meta?.renderer || meta?.pageCount ? (
            <span className="pdf-canvas-renderer">
              {meta.renderer ? pdfRendererLabel(meta.renderer) : ""}
              {meta.pageCount && meta.pageCount > 0 ? ` · ${meta.pageCount} 页` : ""}
            </span>
          ) : null}
        </div>
        <div className="pdf-canvas-actions" role="group" aria-label="PDF 翻页控制">
          <div className="pdf-canvas-nav">
            <button
              type="button"
              className="icon-button compact"
              onClick={() => setCurrentPage(1)}
              disabled={state !== "ready" || currentPage <= 1}
              aria-label="第一页"
            >
              <span>1</span>
            </button>
            <button
              type="button"
              className="icon-button compact"
              onClick={() => setCurrentPage((page) => Math.max(1, page - 1))}
              disabled={state !== "ready" || currentPage <= 1}
              aria-label="上一页"
            >
              <ArrowLeft size={16} />
            </button>
            <button
              type="button"
              className="icon-button compact"
              onClick={() => setCurrentPage((page) => Math.min(pageCount || page, page + 1))}
              disabled={state !== "ready" || pageCount <= 1 || currentPage >= pageCount}
              aria-label="下一页"
            >
              <ArrowRight size={16} />
            </button>
            <button
              type="button"
              className="icon-button compact"
              onClick={() => setCurrentPage(pageCount || 1)}
              disabled={state !== "ready" || pageCount <= 1 || currentPage >= pageCount}
              aria-label="最后一页"
            >
              <span>末</span>
            </button>
          </div>
          <label className="pdf-page-jump">
            <span className="pdf-page-jump-label">页码</span>
            <input
              type="number"
              min={1}
              max={pageCount || 1}
              value={pageInput}
              onChange={(event) => setPageInput(event.target.value)}
              onBlur={commitPageInput}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  commitPageInput();
                }
              }}
              aria-label="跳转到页码"
              className="pdf-page-input"
            />
            <span className="pdf-page-total">/ {pageCount || 1}</span>
          </label>
          <a href={pdfUrl} target="_blank" rel="noreferrer" className="pdf-canvas-open-link">
            <ExternalLink size={14} />
            <span>打开原始 PDF</span>
          </a>
        </div>
      </div>
      <div className={state === "ready" ? "pdf-canvas-page" : "pdf-canvas-page loading"} data-current-page={currentPage}>
        {state === "loading" ? <Loader2 className="spin" size={18} /> : null}
        {state === "failed" ? (
          <div className="handout-preview-placeholder compact">
            <FileText size={20} />
            <strong>PDF 已生成</strong>
            <span>当前浏览器无法渲染 Canvas 预览，可以打开原始 PDF 或直接下载。</span>
          </div>
        ) : null}
        <canvas
          ref={canvasRef}
          className="pdf-page-canvas"
          data-page={currentPage}
          aria-label={canvasLabel}
        />
      </div>
    </div>
  );
}

export function pdfRendererLabel(renderer: string) {
  const labels: Record<string, string> = {
    xelatex: "XeLaTeX 编译",
    pdfbox_fallback: "后备排版",
  };
  return labels[renderer] ?? (renderer || "渲染方式未知");
}
