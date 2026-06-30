package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherFeishuDiscoveryResponse;

/**
 * Client abstraction for remote Feishu folder listing and keyword discovery.
 */
@FunctionalInterface
public interface TeacherFeishuDiscoveryClient {

    /**
     * Discovers remote Feishu candidates without downloading their content.
     *
     * @param query normalized discovery query
     * @return discovered Feishu candidates
     */
    TeacherFeishuDiscoveryResponse discover(TeacherFeishuDiscoveryQuery query);
}
