package com.doob.mathagent.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class FeishuCredentialCipherTest {

    @Test
    void encryptsAndDecryptsWithoutStoringPlaintext() {
        FeishuCredentialCipher cipher = new FeishuCredentialCipher(
                Base64.getEncoder().encodeToString(new byte[32]));

        String encrypted = cipher.encrypt("user-access-token");

        assertThat(encrypted).isNotBlank().doesNotContain("user-access-token");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("user-access-token");
    }

    @Test
    void rejectsInvalidKeyLength() {
        assertThatThrownBy(() -> new FeishuCredentialCipher("c2hvcnQ="))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
