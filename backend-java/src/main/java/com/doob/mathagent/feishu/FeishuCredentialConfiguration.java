package com.doob.mathagent.feishu;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

/**
 * Creates the credential cipher from the versioned backend configuration.
 *
 * <p>The deployment intentionally has one configuration source: requiring a separate process environment
 * variable made a Compose restart behave differently from the checked-in local startup contract.</p>
 */
@Configuration
public class FeishuCredentialConfiguration {
    @Bean
    FeishuCredentialCipher feishuCredentialCipher(
            @Value("${math-agent.feishu.token-encryption-key}") String tokenEncryptionKey) {
        return new FeishuCredentialCipher(tokenEncryptionKey);
    }
}
