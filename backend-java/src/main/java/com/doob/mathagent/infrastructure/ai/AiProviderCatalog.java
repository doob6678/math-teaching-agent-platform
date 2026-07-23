package com.doob.mathagent.infrastructure.ai;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 已配置的 AI 提供商目录。
 *
 * <p>该目录特意与 Spring AI 模型 Bean 分离。它允许工作流记录任务可使用的提供商，向前端提供安全的模型目录，
 * 并防止 API 密钥缺失的提供商被静默选中。</p>
 */
@Component
public class AiProviderCatalog {

    private final AiProviderProperties properties;

    /**
     * 创建提供商目录。
     *
     * @param properties 从环境变量读取的提供商配置
     */
    public AiProviderCatalog(AiProviderProperties properties) {
        this.properties = properties;
    }

    /**
     * 按后端回退顺序返回已启用的提供商。
     *
     * @return 已启用的提供商
     */
    public List<Provider> enabledProviders() {
        return configuredProviders()
                .stream()
                .filter(AiProviderCatalog::hasUsableCredentials)
                .map(AiProviderCatalog::toProvider)
                .sorted((left, right) -> Integer.compare(
                        providerOrder(properties.getDefaultProvider(), left.name()),
                        providerOrder(properties.getDefaultProvider(), right.name())))
                .toList();
    }

    /**
     * 根据后端配置和允许列表构建前端安全的模型目录。
     *
     * @return 不包含 API 密钥的提供商和模型目录
     */
    public ModelCatalog modelCatalog() {
        Provider defaultProvider = defaultProvider();
        List<ModelProvider> providers = enabledProviders().stream()
                .map(provider -> new ModelProvider(
                        provider.name(),
                        true,
                        provider.chatModel(),
                        allowedModelOptions(provider.name())))
                .sorted((left, right) -> Integer.compare(
                        providerOrder(defaultProvider.name(), left.name()),
                        providerOrder(defaultProvider.name(), right.name())))
                .toList();
        return new ModelCatalog(
                defaultProvider.name(),
                defaultProvider.chatModel(),
                providers.stream().map(ModelProvider::name).toList(),
                providers);
    }

    /**
     * 按名称查找已启用的提供商。
     *
     * @param name 提供商名称
     * @return 已配置时返回对应的已启用提供商
     */
    public Optional<Provider> provider(String name) {
        String normalized = normalize(name);
        return enabledProviders().stream()
                .filter(provider -> provider.name().equals(normalized))
                .findFirst();
    }

    /**
     * 查找已启用的提供商，并校验请求的模型是否属于该提供商的允许列表。
     *
     * @param providerName 用户偏好中请求的提供商名称
     * @param modelCode 用户偏好中请求的模型编码
     * @return 请求模型在允许列表中时，返回使用该模型的提供商
     */
    public Optional<Provider> preferredProvider(String providerName, String modelCode) {
        String normalizedModel = safeText(modelCode);
        if (normalizedModel.isBlank()) {
            return Optional.empty();
        }
        return provider(providerName)
                .filter(provider -> allowedModels(provider.name()).contains(normalizedModel))
                .map(provider -> new Provider(provider.name(), provider.baseUrl(), normalizedModel));
    }

    /**
     * 返回已配置的默认提供商。
     *
     * @return 默认提供商
     */
    public Provider defaultProvider() {
        return provider(properties.getDefaultProvider())
                .or(() -> enabledProviders().stream().findFirst())
                .orElseThrow(() -> new IllegalStateException("No AI provider is enabled by environment variables"));
    }

    /**
     * 按指定的回退顺序返回已配置的提供商。
     */
    private List<AiProviderProperties.Provider> configuredProviders() {
        return List.of(
                properties.getOpenai(),
                properties.getDashscope(),
                properties.getDeepseek(),
                properties.getArk());
    }

    /**
     * 检查提供商配置是否完整到足以使用。
     *
     * @param provider 提供商配置
     * @return 名称、基础地址、API 密钥和聊天模型均存在时返回 true
     */
    private static boolean hasUsableCredentials(AiProviderProperties.Provider provider) {
        return hasText(provider.getName())
                && hasText(provider.getBaseUrl())
                && hasText(provider.getApiKey())
                && hasText(provider.getChatModel());
    }

    /**
     * 将可变的配置属性转换为不可变的运行时提供商对象。
     *
     * @param provider 提供商配置
     * @return 运行时提供商对象
     */
    private static Provider toProvider(AiProviderProperties.Provider provider) {
        return new Provider(
                normalize(provider.getName()),
                provider.getBaseUrl().strip(),
                provider.getChatModel().strip());
    }

    /**
     * 规范化提供商名称，以便进行稳定比较。
     *
     * @param value 提供商名称
     * @return 规范化后的提供商名称
     */
    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * 返回前端可以为指定提供商请求的模型编码允许列表。
     *
     * @param providerName 规范化后的提供商名称
     * @return 允许使用的模型编码
     */
    private static List<String> allowedModels(String providerName) {
        return switch (normalize(providerName)) {
            // Terra is the verified low-latency default; Luna remains an explicit opt-in model in the allow-list.
            
            case "openai" -> List.of("gpt-5.6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.5", "gpt-5.4", "gpt-5.4-mini", "gpt-5.4-nano");
            case "dashscope" -> List.of("qwen3.6-flash", "qwen3.7-plus", "qwen3.7-max");
            case "deepseek" -> List.of("deepseek-v4-flash", "deepseek-v4-pro");
            case "ark" -> List.of("doubao-seed-2-0-lite-260428", "doubao-seed-2.0-mini");
            default -> List.of();
        };
    }

    /**
     * 返回带有粗粒度能力和费用标签的前端安全模型选项。
     */
    private static List<ModelOption> allowedModelOptions(String providerName) {
        return allowedModels(providerName).stream()
                .map(model -> new ModelOption(model, modelLevel(model), priceTier(model)))
                .toList();
    }

    /**
     * 将默认提供商置于首位，其余提供商按已配置的回退顺序排列。
     */
    private static int providerOrder(String defaultProviderName, String providerName) {
        if (normalize(defaultProviderName).equals(normalize(providerName))) {
            return -1;
        }
        return switch (normalize(providerName)) {
            case "openai" -> 0;
            case "dashscope" -> 1;
            case "deepseek" -> 2;
            case "ark" -> 3;
            default -> 99;
        };
    }

    /**
     * 将允许列表中的模型映射为粗粒度的路由能力标签。
     */
    private static String modelLevel(String modelCode) {
        String normalized = normalize(modelCode);
        if (normalized.contains("max") || normalized.equals("gpt-5.4") || normalized.contains("gpt-5.6") || normalized.endsWith("-pro")) {
            return "reasoning";
        }
        if (normalized.contains("mini") || normalized.contains("nano")
                || normalized.contains("flash") || normalized.contains("lite")) {
            return "fast_text";
        }
        return "general";
    }

    /**
     * 将模型编码映射为仅用于界面提示的粗粒度价格标签。
     */
    private static String priceTier(String modelCode) {
        String normalized = normalize(modelCode);
        Set<String> cheapHints = Set.of("mini", "nano", "flash", "lite", "turbo");
        return cheapHints.stream().anyMatch(normalized::contains) ? "cheap" : "standard";
    }

    /**
     * 返回去除首尾空白后的文本；文本为空时返回空字符串。
     *
     * @param value 文本值
     * @return 去除首尾空白后的文本
     */
    private static String safeText(String value) {
        return value == null || value.isBlank() ? "" : value.strip();
    }

    /**
     * 判断字符串是否包含非空白文本。
     *
     * @param value 文本值
     * @return 不为空白时返回 true
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 用于运行时的提供商视图，可安全用于日志记录和审计。
     *
     * @param name 提供商名称
     * @param baseUrl 兼容 OpenAI 接口的基础地址
     * @param chatModel 聊天模型名称
     */
    public record Provider(String name, String baseUrl, String chatModel) {
    }

    /**
     * 不包含提供商敏感信息的前端安全模型目录。
     *
     * @param defaultProviderName 后端默认提供商
     * @param defaultModelCode 后端默认模型
     * @param fallbackProviderOrder 提供商轮换顺序
     * @param providers 已启用的提供商及其模型选项
     */
    public record ModelCatalog(
            String defaultProviderName,
            String defaultModelCode,
            List<String> fallbackProviderOrder,
            List<ModelProvider> providers) {
    }

    /**
     * 前端安全的提供商模型列表。
     *
     * @param name 提供商名称
     * @param enabled 是否已配置凭据
     * @param defaultModelCode 提供商默认模型
     * @param models 允许使用的模型选项
     */
    public record ModelProvider(
            String name,
            boolean enabled,
            String defaultModelCode,
            List<ModelOption> models) {
    }

    /**
     * 允许列表中的一个模型选项。
     *
     * @param modelCode 提供商模型编码
     * @param modelLevel 粗粒度模型能力标签
     * @param priceTier 粗粒度价格标签
     */
    public record ModelOption(String modelCode, String modelLevel, String priceTier) {
    }
}
