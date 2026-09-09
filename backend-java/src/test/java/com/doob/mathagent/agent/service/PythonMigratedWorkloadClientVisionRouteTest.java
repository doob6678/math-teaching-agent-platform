package com.doob.mathagent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 带图轮次的 provider 路由签发测试（2026-09-06）。
 *
 * <p>学生对话把题图原样送进 ReAct 消息，而 deepseek/glm 实测静默丢图，因此 imageRequired 时
 * primary 与 fallback 都必须是探针验证过的视觉模型；用户显式偏好非视觉模型时降级到视觉默认。</p>
 */
class PythonMigratedWorkloadClientVisionRouteTest {

    @Test
    void imageTurnRoutesToVisionDefaultIgnoringTextOnlyDefault() {
        Map<String, Object> route = client().providerRoute("run-1", "student_explanation", "", "", true);
        assertThat(primary(route).get("name")).isEqualTo("openai");
        assertThat(primary(route).get("model")).isEqualTo("gpt-5.6-terra");
        // fallback 只允许视觉模型：同提供商其余已验证视觉模型依次补位，deepseek/glm 不得进入带图轮换。
        assertThat(fallbackNames(route)).containsOnly("openai");
        assertThat(fallbackModels(route)).containsExactly("gpt-5.6-luna", "gpt-5.6-sol", "gpt-5.5");
    }

    @Test
    void imageTurnRejectsExplicitTextOnlyModelInsteadOfSilentDowngrade() {
        // 老板 2026-09-06 拍板：选择即绑定。显式选文本模型又带图时不降级，直接报错让前端提示。
        assertThatThrownBy(() ->
                client().providerRoute("run-2", "student_explanation", "deepseek", "deepseek-v4-flash", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持图片输入");
    }

    @Test
    void imageTurnKeepsExplicitVisionGlmModel() {
        // glm-5.3-flash 实测能看图（桥接已补 image 块转换），显式选择必须原样保留。
        Map<String, Object> route =
                client().providerRoute("run-2b", "student_explanation", "glm", "glm-5.3-flash", true);
        assertThat(primary(route).get("name")).isEqualTo("glm");
        assertThat(primary(route).get("model")).isEqualTo("glm-5.3-flash");
    }

    @Test
    void imageTurnKeepsVisionPreferredModel() {
        Map<String, Object> route =
                client().providerRoute("run-3", "student_explanation", "openai", "gpt-5.5", true);
        assertThat(primary(route).get("name")).isEqualTo("openai");
        assertThat(primary(route).get("model")).isEqualTo("gpt-5.5");
    }

    @Test
    void textTurnKeepsExistingDefaultBehaviour() {
        Map<String, Object> route = client().providerRoute("run-4", "student_explanation", "", "", false);
        assertThat(primary(route).get("name")).isEqualTo("deepseek");
        assertThat(primary(route).get("model")).isEqualTo("deepseek-v4-flash");
    }

    @Test
    void textTurnKeepsExistingPreferredModelBehaviour() {
        Map<String, Object> route =
                client().providerRoute("run-5", "student_explanation", "glm", "glm-5.3-flash", false);
        assertThat(primary(route).get("name")).isEqualTo("glm");
        assertThat(primary(route).get("model")).isEqualTo("glm-5.3-flash");
    }

    private static PythonMigratedWorkloadClient client() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("math-agent.ai.route-grant-secret", "vision-route-test-key");
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("deepseek");
        properties.getOpenai().setEnabled(true);
        properties.getOpenai().setChatModel("gpt-5.6-terra");
        properties.getDeepseek().setEnabled(true);
        properties.getGlm().setEnabled(true);
        return new PythonMigratedWorkloadClient(
                environment, new AiProviderCatalog(properties), new ProviderRouteGrantSigner(environment));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> primary(Map<String, Object> route) {
        return (Map<String, String>) route.get("primary");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> fallbacks(Map<String, Object> route) {
        return (List<Map<String, String>>) route.get("fallbacks");
    }

    private static List<String> fallbackNames(Map<String, Object> route) {
        return fallbacks(route).stream().map(item -> item.get("name")).toList();
    }

    private static List<String> fallbackModels(Map<String, Object> route) {
        return fallbacks(route).stream().map(item -> item.get("model")).toList();
    }
}
