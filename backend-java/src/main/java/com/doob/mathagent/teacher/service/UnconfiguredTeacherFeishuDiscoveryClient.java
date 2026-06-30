package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherFeishuDiscoveryResponse;

/**
 * Fallback Feishu discovery client that fails clearly when no process-backed client is configured.
 */
public class UnconfiguredTeacherFeishuDiscoveryClient implements TeacherFeishuDiscoveryClient {

    /**
     * Fails fast instead of returning fake Feishu candidates.
     */
    @Override
    public TeacherFeishuDiscoveryResponse discover(TeacherFeishuDiscoveryQuery query) {
        throw new IllegalStateException("Feishu discovery is not configured");
    }
}
