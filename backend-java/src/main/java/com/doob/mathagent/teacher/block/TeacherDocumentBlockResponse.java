package com.doob.mathagent.teacher.block;

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
 * @param sourcePath stable relative source path used for incremental sync and in-document rerank
 * @param blockRole coarse semantic role such as question, analysis, method, template, or tip
 * @param rawText original extracted text
 * @param normalizedText normalized text used by lexical/vector retrieval
 * @param imageRefs JSON array string of extracted image references
 * @param formulaRefs JSON array string of extracted formula references
 * @param graphNodeIdsJson JSON array string of normalized knowledge-graph node ids aligned to this block
 * @param graphTagNamesJson JSON array string of normalized graph tag names aligned to this block
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
        String sourcePath,
        String blockRole,
        String rawText,
        String normalizedText,
        String imageRefs,
        String formulaRefs,
        String graphNodeIdsJson,
        String graphTagNamesJson,
        String checksum,
        double confidence,
        String status) {

    /**
     * Backward-compatible constructor used by older tests and call sites that do not yet set
     * source path, block role, or cached graph alignment metadata.
     */
    public TeacherDocumentBlockResponse(
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
        this(
                blockId,
                documentId,
                externalBlockId,
                blockType,
                blockOrder,
                chapter,
                section,
                pageNo,
                printedPageNo,
                "",
                "reference",
                rawText,
                normalizedText,
                imageRefs,
                formulaRefs,
                "[]",
                "[]",
                checksum,
                confidence,
                status);
    }
}

