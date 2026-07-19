package com.doob.mathagent.retrieval;

/**
 * One CLIP-ranked textbook page hit.
 *
 * @param score clip similarity score
 * @param docId textbook doc id
 * @param bookName textbook title
 * @param chapterPath normalized chapter path from the prebuilt page-image index
 * @param pageNo pdf page number
 * @param printedPageNo printed page marker when available
 * @param sectionTitle section title when available
 * @param text page text snippet stored in the page-image index
 * @param imageUri backend-controlled textbook page image URL
 */
public record TextbookPageImageSearchHit(
        double score,
        String docId,
        String bookName,
        String chapterPath,
        int pageNo,
        String printedPageNo,
        String sectionTitle,
        String text,
        String imageUri) {
}
