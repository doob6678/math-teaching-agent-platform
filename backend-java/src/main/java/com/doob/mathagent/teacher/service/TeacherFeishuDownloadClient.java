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
    FeishuDownloadResult download(String url, Path stagingRoot, int maxFiles);

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
