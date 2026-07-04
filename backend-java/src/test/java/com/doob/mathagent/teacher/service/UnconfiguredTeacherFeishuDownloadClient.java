package com.doob.mathagent.teacher.service;

import java.nio.file.Path;

/**
 * Test-only Feishu downloader that verifies failure paths without a real Feishu process.
 */
public class UnconfiguredTeacherFeishuDownloadClient implements TeacherFeishuDownloadClient {

    @Override
    public FeishuDownloadResult download(
            String url,
            Path stagingRoot,
            int maxFiles,
            String fileExtension,
            FeishuDownloadCheckpoint checkpoint) {
        throw new IllegalStateException("Feishu downloader is not configured");
    }
}
