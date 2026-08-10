package com.doob.mathagent.teacher.feishu;

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
        return download(url, stagingRoot, maxFiles, "md", checkpoint);
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
    FeishuDownloadResult download(
            String url,
            Path stagingRoot,
            int maxFiles,
            String fileExtension,
            FeishuDownloadCheckpoint checkpoint);

    /** Downloads with a decrypted user OAuth token kept only for this backend process invocation. */
    default FeishuDownloadResult downloadWithAccessToken(
            String url, Path stagingRoot, int maxFiles, String fileExtension,
            FeishuDownloadCheckpoint checkpoint, String accessToken) {
        return download(url, stagingRoot, maxFiles, fileExtension, checkpoint);
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
     * @param checkpoint latest traversal checkpoint returned by the downloader
     * @param downloadedItemsJson JSON array of successfully downloaded Feishu items
     * @param failedItemsJson JSON array of failed Feishu items, when the worker reports item-level failures
     * @param providerTitle actual provider display title when the URL identifies a document
     * @param providerRevision actual provider revision when Feishu exposes one
     * @param discoveredItemsJson all remote file metadata discovered during this run
     * @param changedItemsJson remote files whose local body changed or was missing
     * @param unchangedItemsJson remote files confirmed unchanged and present locally
     */
    record FeishuDownloadResult(
            Path savedPath,
            int files,
            int skipped,
            int failed,
            String message,
            FeishuDownloadCheckpoint checkpoint,
            String downloadedItemsJson,
            String failedItemsJson,
            String providerTitle,
            String providerRevision,
            String discoveredItemsJson,
            String changedItemsJson,
            String unchangedItemsJson) {

        /** Compatibility constructor for download clients predating remote title/revision metadata. */
        public FeishuDownloadResult(
                Path savedPath,
                int files,
                int skipped,
                int failed,
                String message,
                FeishuDownloadCheckpoint checkpoint,
                String downloadedItemsJson,
                String failedItemsJson) {
            this(savedPath, files, skipped, failed, message, checkpoint, downloadedItemsJson, failedItemsJson,
                    null, null, "[]", legacyChangedItems(files), "[]");
        }

        /**
         * Legacy adapters do not provide provider metadata, so a non-empty download must be treated as changed.
         * Otherwise the sync executor could skip parsing and vector rebuild on the first run merely because an old
         * adapter returned the compatibility constructor instead of the incremental manifest fields.
         */
        private static String legacyChangedItems(int files) {
            return files > 0 ? "[{\"legacy\":true}]" : "[]";
        }

        /**
         * Normalizes optional JSON and checkpoint fields.
         */
        public FeishuDownloadResult {
            checkpoint = checkpoint == null ? FeishuDownloadCheckpoint.empty() : checkpoint;
            downloadedItemsJson = jsonArrayOrEmpty(downloadedItemsJson);
            failedItemsJson = jsonArrayOrEmpty(failedItemsJson);
            discoveredItemsJson = jsonArrayOrEmpty(discoveredItemsJson);
            changedItemsJson = jsonArrayOrEmpty(changedItemsJson);
            unchangedItemsJson = jsonArrayOrEmpty(unchangedItemsJson);
            providerTitle = blankToNull(providerTitle);
            providerRevision = blankToNull(providerRevision);
        }

        /**
         * Keeps persisted JSON arrays valid.
         */
        private static String jsonArrayOrEmpty(String value) {
            if (value == null || value.isBlank()) {
                return "[]";
            }
            String normalized = value.strip();
            return normalized.startsWith("[") && normalized.endsWith("]") ? normalized : "[]";
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }
}
