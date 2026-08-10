package com.doob.mathagent.infrastructure.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Environment-backed AI provider configuration.
 *
 * <p>Java stores only provider route metadata used to issue scoped grants. Provider endpoints and credentials remain
 * exclusively in the Python worker.</p>
 */
@Component
@ConfigurationProperties("math-agent.ai")
public class AiProviderProperties {

    /** Default provider name used when a workflow does not specify a provider. */
    private String defaultProvider = "openai";

    /** Java-owned route metadata for Qwen provider/model selection. */
    private Provider dashscope = new Provider("dashscope", false, "qwen3.6-flash");

    /** Java-owned route metadata for OpenAI-compatible provider/model selection. */
    private Provider openai = new Provider("openai", false, "gpt-5.6-luna");

    /** Java-owned route metadata for DeepSeek provider/model selection. */
    private Provider deepseek = new Provider("deepseek", false, "deepseek-v4-flash");

    /** Java-owned route metadata for Ark provider/model selection. */
    private Provider ark = new Provider("ark", false, "doubao-seed-2-0-lite-260428");

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

        /** Whether Java may include this provider/model route in a signed Python grant. */
        private boolean enabled;

        /** Chat model name authorized for the provider route. */
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
         * @param enabled whether Java may grant this Python provider route
         * @param chatModel default chat model
         */
        public Provider(String name, boolean enabled, String chatModel) {
            this.name = name;
            this.enabled = enabled;
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
         * Returns whether Java may issue a Python route grant for this provider.
         *
         * @return true when the provider route is enabled
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Enables or disables this provider route without accepting provider credentials.
         *
         * @param enabled whether the route is allowed
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
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
