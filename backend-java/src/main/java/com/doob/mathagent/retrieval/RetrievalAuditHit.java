package com.doob.mathagent.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单条检索命中审计记录，对应 retrieval_hit_log 的核心字段。
 */
public record RetrievalAuditHit(
        /** 当前查询内的排序名次，从 1 开始。 */
        int rankNo,
        /** 命中的教材 chunk 唯一标识。 */
        String chunkId,
        /** 教材文档 ID，用于按书本追踪证据来源。 */
        String docId,
        /** 教材显示名称。 */
        String bookName,
        /** PDF 页码；可为空以兼容非 PDF 资料源。 */
        Integer pageNo,
        /** 教材印刷页码；OCR 未识别时可能为空或“未识别”。 */
        String printedPageNo,
        /** BM25/重排后的命中分数。 */
        double score,
        /** 产生该命中的召回策略，例如 local_bm25。 */
        String retrievalStrategy,
        /** 页面质量标签，例如 content_page、cover_page、toc_page。 */
        String pageQualityLabel,
        /** 源页面图片相对路径，用于前端预览和人工复核。 */
        String sourcePageImage,
        /** 册别信息，写入 evidence_json 便于人工审计。 */
        String volume,
        /** 章节路径，写入 evidence_json 便于定位证据上下文。 */
        List<String> chapterPath,
        /** 命中小节标题，写入 evidence_json 便于展示。 */
        String sectionTitle,
        /** 截断后的正文片段，写入 evidence_json 便于离线排查。 */
        String textSnippet,
        /** 命中页公式文本，写入 evidence_json 便于公式检索质量分析。 */
        String formulaText) {

    public static RetrievalAuditHit from(int rankNo, TextbookSearchHit hit) {
        return new RetrievalAuditHit(
                rankNo,
                hit.chunkId(),
                hit.docId(),
                hit.bookName(),
                hit.pageNo(),
                hit.printedPageNo(),
                hit.score(),
                hit.retrievalStrategy(),
                hit.pageQualityLabel(),
                hit.sourcePageImage(),
                hit.volume(),
                hit.chapterPath(),
                hit.sectionTitle(),
                hit.textSnippet(),
                hit.formulaText());
    }

    Map<String, Object> evidenceJson() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("volume", volume);
        values.put("chapter_path", chapterPath == null ? List.of() : chapterPath);
        values.put("section_title", sectionTitle);
        values.put("text_snippet", textSnippet);
        values.put("formula_text", formulaText);
        return values;
    }
}
