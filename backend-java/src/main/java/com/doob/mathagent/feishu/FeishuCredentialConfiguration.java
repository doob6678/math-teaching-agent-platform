package com.doob.mathagent.feishu;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Creates the cipher from a backend-only environment variable; no browser-facing configuration is accepted. */
@Configuration
public class FeishuCredentialConfiguration {
    @Bean
    FeishuCredentialCipher feishuCredentialCipher(Environment environment) {
        return new FeishuCredentialCipher(environment.getRequiredProperty("FEISHU_TOKEN_ENCRYPTION_KEY"));
    }
}
