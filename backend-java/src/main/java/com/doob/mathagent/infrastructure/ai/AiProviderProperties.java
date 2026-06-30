package com.doob.mathagent.infrastructure.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Environment-backed AI provider configuration.
 *
 * <p>Secrets are never hard-coded. Each provider reads api-key, base-url, and model from application.yml placeholders,
 * which in turn read environment variables.</p>
 */
@Component
@ConfigurationProperties("math-agent.ai")
public class AiProviderProperties {

    /** Default provider name used when a workflow does not specify a provider. */
    private String defaultProvider = "openai";

    /** Alibaba Cloud Model Studio Qwen provider configuration. */
    private Provider dashscope = new Provider(
            "dashscope",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "",
            "qwen3.6-flash");

    /** OpenAI-compatible GPT provider configuration. */
    private Provider openai = new Provider("openai", "https://api.openai.com", "", "gpt-5.4");

    /** DeepSeek OpenAI-compatible provider configuration. */
    private Provider deepseek = new Provider("deepseek", "https://api.deepseek.com", "", "deepseek-v4-flash");

    /** Volcengine Ark/Doubao OpenAI-compatible provider configuration. */
    private Provider ark = new Provider("ark", "https://ark.cn-beijing.volces.com/api/v3", "", "doubao-seed-2-0-lite-260428");

    /**
     * Returns the default provider name.
     *
     * @return default provider name
     */
    public String getDefaultProvider() {
        return defaultProvider;
    }

    /**
     * Sets the default provider name.
     *
     * @param defaultProvider default provider name
     */
    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    /**
     * Returns DashScope provider configuration.
     *
     * @return DashScope configuration
     */
    public Provider getDashscope() {
        return dashscope;
    }

    /**
     * Sets DashScope provider configuration.
     *
     * @param dashscope DashScope configuration
     */
    public void setDashscope(Provider dashscope) {
        this.dashscope = dashscope;
    }

    /**
     * Returns OpenAI provider configuration.
     *
     * @return OpenAI configuration
     */
    public Provider getOpenai() {
        return openai;
    }

    /**
     * Sets OpenAI provider configuration.
     *
     * @param openai OpenAI configuration
     */
    public void setOpenai(Provider openai) {
        this.openai = openai;
    }

    /**
     * Returns DeepSeek provider configuration.
     *
     * @return DeepSeek configuration
     */
    public Provider getDeepseek() {
        return deepseek;
    }

    /**
     * Sets DeepSeek provider configuration.
     *
     * @param deepseek DeepSeek configuration
     */
    public void setDeepseek(Provider deepseek) {
        this.deepseek = deepseek;
    }

    /**
     * Returns Ark provider configuration.
     *
     * @return Ark configuration
     */
    public Provider getArk() {
        return ark;
    }

    /**
     * Sets Ark provider configuration.
     *
     * @param ark Ark configuration
     */
    public void setArk(Provider ark) {
        this.ark = ark;
    }

    /**
     * Mutable provider settings bound by Spring Boot configuration properties.
     */
    public static class Provider {

        /** Stable provider name used by task configuration and audit records. */
        private String name;

        /** OpenAI-compatible API base URL. */
        private String baseUrl;

        /** API key read from an environment variable. */
        private String apiKey;

        /** Chat model name sent to the provider. */
        private String chatModel;

        /**
         * Creates empty provider settings for Spring Boot binding.
         */
        public Provider() {
        }

        /**
         * Creates provider settings.
         *
         * @param name provider name
         * @param baseUrl API base URL
         * @param apiKey API key
         * @param chatModel chat model
         */
        public Provider(String name, String baseUrl, String apiKey, String chatModel) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.chatModel = chatModel;
        }

        /**
         * Returns the provider name.
         *
         * @return provider name
         */
        public String getName() {
            return name;
        }

        /**
         * Sets the provider name.
         *
         * @param name provider name
         */
        public void setName(String name) {
            this.name = name;
        }

        /**
         * Returns the API base URL.
         *
         * @return API base URL
         */
        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * Sets the API base URL.
         *
         * @param baseUrl API base URL
         */
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        /**
         * Returns the API key.
         *
         * @return API key
         */
        public String getApiKey() {
            return apiKey;
        }

        /**
         * Sets the API key.
         *
         * @param apiKey API key
         */
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        /**
         * Returns the chat model name.
         *
         * @return chat model name
         */
        public String getChatModel() {
            return chatModel;
        }

        /**
         * Sets the chat model name.
         *
         * @param chatModel chat model name
         */
        public void setChatModel(String chatModel) {
            this.chatModel = chatModel;
        }
    }
}
