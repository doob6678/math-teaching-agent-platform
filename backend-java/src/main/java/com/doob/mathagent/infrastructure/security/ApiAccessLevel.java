package com.doob.mathagent.infrastructure.security;

/**
 * API 安全等级，用于把接口按调用成本和数据风险分层。
 */
public enum ApiAccessLevel {
    PUBLIC,
    GUEST,
    USER,
    ADMIN
}
