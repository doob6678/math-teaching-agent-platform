package com.doob.mathagent.teacher.vo;

/**
 * Parsed content block belonging to a teacher-managed source document.
 *
 * @param blockId stable block id used by RAG citations
 * @param documentId source document id
 * @param externalBlockId source-specific block id, such as a file-relative path and segment index
 * @param blockType normalized block type, such as markdown, text, or pdf_text
 * @param blockOrder order inside the source document
 * @param chapter chapter heading inferred from Markdown or document metadata
 * @param section section heading inferred from Markdown or document metadata
 * @param pageNo page number when the source is paged
 * @param printedPageNo printed page label when available
 * @param rawText original extracted text
 * @param normalizedText normalized text used by lexical/vector retrieval
 * @param imageRefs JSON array string of extracted image references
 * @param formulaRefs JSON array string of extracted formula references
 * @param checksum SHA-256 checksum of normalized text
 * @param confidence extraction confidence from 0 to 1
 * @param status block lifecycle status, such as active or inactive
 */
public record TeacherDocumentBlockResponse(
        String blockId,
        String documentId,
        String externalBlockId,
        String blockType,
        int blockOrder,
        String chapter,
        String section,
        Integer pageNo,
        String printedPageNo,
        String rawText,
        String normalizedText,
        String imageRefs,
        String formulaRefs,
        String checksum,
        double confidence,
        String status) {
}
