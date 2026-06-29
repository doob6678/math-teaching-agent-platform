package com.doob.mathagent.teacher.service;

import java.nio.file.Path;

/**
 * Fallback Feishu downloader that fails clearly when no real downloader bean is configured.
 */
public class UnconfiguredTeacherFeishuDownloadClient implements TeacherFeishuDownloadClient {

    /**
     * Fails fast when no process-backed Feishu downloader is configured.
     */
    @Override
    public FeishuDownloadResult download(String url, Path stagingRoot, int maxFiles) {
        throw new IllegalStateException("Feishu downloader is not configured");
    }
}
