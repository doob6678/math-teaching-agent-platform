package com.doob.mathagent.infrastructure.security;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Component;

/**
 * Sa-Token bridge for resolving the current authenticated subject.
 *
 * <p>The existing header-based access filter remains in place for local tests. This adapter gives the next permission
 * stage a narrow integration point for replacing anonymous headers with Sa-Token login state.</p>
 */
@Component
public class SaTokenSubjectResolver {

    /**
     * Returns the current Sa-Token login id when available.
     *
     * @return login id, or null when no subject is logged in
     */
    public String currentLoginIdOrNull() {
        try {
            return StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
