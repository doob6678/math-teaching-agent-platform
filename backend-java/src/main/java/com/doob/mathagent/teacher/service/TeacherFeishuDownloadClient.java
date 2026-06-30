package com.doob.mathagent.teacher.service;

import java.nio.file.Path;

/**
 * Downloads Feishu/Lark resources to a local staging directory.
 */
public interface TeacherFeishuDownloadClient {

    /**
     * Downloads a Feishu browser URL into a staging directory.
     *
     * @param url Feishu browser URL
     * @param stagingRoot local staging root
     * @param maxFiles maximum files to download; 0 means no downloader-level limit
     * @return download result with a local path and statistics
     */
    default FeishuDownloadResult download(String url, Path stagingRoot, int maxFiles) {
        return download(url, stagingRoot, maxFiles, FeishuDownloadCheckpoint.empty());
    }

    /**
     * Downloads a Feishu browser URL in the requested native export format.
     *
     * @param url Feishu browser URL
     * @param stagingRoot local staging root
     * @param maxFiles maximum files to download; 0 means no downloader-level limit
     * @param fileExtension selected Feishu export format; supported values are md, docx, and pdf
     * @return download result with a local path and statistics
     */
    default FeishuDownloadResult download(String url, Path stagingRoot, int maxFiles, String fileExtension) {
        return download(url, stagingRoot, maxFiles, fileExtension, FeishuDownloadCheckpoint.empty());
    }

    /**
     * Downloads a Feishu browser URL from a durable traversal checkpoint.
     *
     * @param url Feishu browser URL
     * @param stagingRoot local staging root
     * @param maxFiles maximum files to download; 0 means no downloader-level limit
     * @param checkpoint durable traversal checkpoint, or empty for a fresh traversal
     * @return download result with a local path and statistics
     */
    default FeishuDownloadResult download(
            String url,
            Path stagingRoot,
            int maxFiles,
            FeishuDownloadCheckpoint checkpoint) {
        return download(url, stagingRoot, maxFiles);
    }

    /**
     * Downloads a Feishu browser URL in the requested native export format from a durable traversal checkpoint.
     *
     * @param url Feishu browser URL
     * @param stagingRoot local staging root
     * @param maxFiles maximum files to download; 0 means no downloader-level limit
     * @param fileExtension selected Feishu export format; supported values are md, docx, and pdf
     * @param checkpoint durable traversal checkpoint, or empty for a fresh traversal
     * @return download result with a local path and statistics
     */
    default FeishuDownloadResult download(
            String url,
            Path stagingRoot,
            int maxFiles,
            String fileExtension,
            FeishuDownloadCheckpoint checkpoint) {
        String normalized = fileExtension == null || fileExtension.isBlank() ? "md" : fileExtension.strip().toLowerCase();
        if (!"md".equals(normalized)) {
            throw new IllegalStateException("Feishu downloader does not support export format: " + normalized);
        }
        return download(url, stagingRoot, maxFiles, checkpoint);
    }

    /**
     * Durable Feishu traversal cursor passed to process-backed downloaders.
     *
     * @param currentFolderToken folder token where traversal stopped
     * @param currentPath human-readable folder path
     * @param pageToken Feishu page token where traversal stopped
     * @param visitedFolderTokensJson visited folder token JSON array
     * @param downloadedItemsJson downloaded item JSON array
     */
    record FeishuDownloadCheckpoint(
            String currentFolderToken,
            String currentPath,
            String pageToken,
            String visitedFolderTokensJson,
            String downloadedItemsJson) {

        /**
         * Empty checkpoint for fresh downloads.
         */
        public static FeishuDownloadCheckpoint empty() {
            return new FeishuDownloadCheckpoint("", "", "", "[]", "[]");
        }

        /**
         * Returns whether this checkpoint has a provider cursor.
         */
        public boolean hasCursor() {
            return currentFolderToken != null && !currentFolderToken.isBlank()
                    || pageToken != null && !pageToken.isBlank()
                    || currentPath != null && !currentPath.isBlank()
                    || visitedFolderTokensJson != null && !"[]".equals(visitedFolderTokensJson.strip())
                    || downloadedItemsJson != null && !"[]".equals(downloadedItemsJson.strip());
        }
    }

    /**
     * Feishu download result returned by the downloader.
     *
     * @param savedPath local path containing downloaded files
     * @param files downloaded file count
     * @param skipped skipped resource count
     * @param failed failed resource count
     * @param message concise downloader message
     */
    record FeishuDownloadResult(Path savedPath, int files, int skipped, int failed, String message) {
    }
}
