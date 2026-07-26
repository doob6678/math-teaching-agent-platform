package com.doob.mathagent.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class FeishuOAuthServiceTest {

    @Test
    void detectsOperatorCredentialAliasesUsedByTheDownloader() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("FEISHU_APPID", "cli-test")
                .withProperty("FEISHU_APPSECRET", "secret-test");

        assertThat(new FeishuOAuthService(environment, null).botCredentialsConfigured()).isTrue();
    }

    @Test
    void requiresBothBotCredentialParts() {
        MockEnvironment environment = new MockEnvironment().withProperty("FEISHU_APP_ID", "cli-test");

        assertThat(new FeishuOAuthService(environment, null).botCredentialsConfigured()).isFalse();
    }
}
