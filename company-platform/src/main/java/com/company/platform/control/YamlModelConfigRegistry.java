/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.embedding.ollama.OllamaTextEmbedding;
import io.agentscope.core.embedding.openai.OpenAITextEmbedding;
import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.core.model.transport.ProxyType;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class YamlModelConfigRegistry implements ModelConfigRegistry, EmbeddingModelRegistry {

    private static final Set<String> OPENAI_PROVIDERS =
            Set.of("openai", "openai-compatible", "openai_compatible");

    private final Map<String, ModelSpec> models = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingModels = new ConcurrentHashMap<>();
    private final PlatformConfigStore configStore;
    private final Environment environment;

    public YamlModelConfigRegistry(PlatformConfigStore configStore, Environment environment) {
        this.configStore = configStore;
        this.environment = environment;
    }

    @PostConstruct
    public void load() throws IOException {
        ModelConfigRoot config =
                configStore.read(PlatformConfigStore.ConfigFile.MODELS, ModelConfigRoot.class);
        Map<String, ModelSpec> loaded = new LinkedHashMap<>();
        for (ModelConfig model : config.models()) {
            ModelSpec spec = normalize(toSpec(model));
            validateSpec(spec);
            if (loaded.containsKey(spec.modelId())) {
                throw new IllegalStateException("Duplicate modelId in config: " + spec.modelId());
            }
            loaded.put(spec.modelId(), spec);
            if (spec.enabled()) {
                register(spec);
            }
        }
        models.clear();
        models.putAll(loaded);
    }

    @Override
    public List<ModelSpec> all() {
        return models.values().stream().toList();
    }

    @Override
    public Optional<ModelSpec> find(String modelId) {
        return Optional.ofNullable(models.get(resolve(modelId)));
    }

    @Override
    public Optional<EmbeddingModel> findEmbeddingModel(String modelId) {
        return Optional.ofNullable(embeddingModels.get(resolve(modelId)));
    }

    @Override
    public List<ModelSpec> allEmbeddingSpecs() {
        return models.values().stream().filter(spec -> "embedding".equals(spec.kind())).toList();
    }

    @Override
    public void upsert(ModelSpec model) {
        ModelSpec normalized = normalize(model);
        validateSpec(normalized);
        models.put(normalized.modelId(), normalized);
        if (normalized.enabled()) {
            register(normalized);
        }
        persist();
    }

    @Override
    public void delete(String modelId) {
        String resolved = resolve(modelId);
        models.remove(resolved);
        embeddingModels.remove(resolved);
        persist();
    }

    private ModelSpec toSpec(ModelConfig model) {
        return new ModelSpec(
                model.modelId(),
                model.kind(),
                model.type(),
                model.provider(),
                model.model(),
                model.className(),
                model.modelFactoryClass(),
                model.apiKey(),
                model.apiKeyEnv(),
                model.baseUrl(),
                model.endpointPath(),
                model.formatterClass(),
                model.stream(),
                model.temperature(),
                model.topP(),
                model.maxTokens(),
                model.maxCompletionTokens(),
                model.frequencyPenalty(),
                model.presencePenalty(),
                model.thinkingBudget(),
                model.reasoningEffort(),
                model.topK(),
                model.seed(),
                model.cacheControl(),
                model.parallelToolCalls(),
                model.additionalHeaders(),
                model.additionalBodyParams(),
                model.additionalQueryParams(),
                model.executionTimeoutMs(),
                model.executionMaxAttempts(),
                model.executionInitialBackoffMs(),
                model.executionMaxBackoffMs(),
                model.executionBackoffMultiplier(),
                model.proxyType(),
                model.proxyHost(),
                model.proxyPort(),
                model.proxyUsername(),
                model.proxyPassword(),
                model.dimensions(),
                model.description(),
                model.enabled());
    }

    private ModelConfig toConfig(ModelSpec spec) {
        return new ModelConfig(
                spec.modelId(),
                spec.kind(),
                spec.type(),
                spec.provider(),
                spec.model(),
                spec.className(),
                spec.modelFactoryClass(),
                spec.apiKey(),
                spec.apiKeyEnv(),
                spec.baseUrl(),
                spec.endpointPath(),
                spec.formatterClass(),
                spec.stream(),
                spec.temperature(),
                spec.topP(),
                spec.maxTokens(),
                spec.maxCompletionTokens(),
                spec.frequencyPenalty(),
                spec.presencePenalty(),
                spec.thinkingBudget(),
                spec.reasoningEffort(),
                spec.topK(),
                spec.seed(),
                spec.cacheControl(),
                spec.parallelToolCalls(),
                spec.additionalHeaders(),
                spec.additionalBodyParams(),
                spec.additionalQueryParams(),
                spec.executionTimeoutMs(),
                spec.executionMaxAttempts(),
                spec.executionInitialBackoffMs(),
                spec.executionMaxBackoffMs(),
                spec.executionBackoffMultiplier(),
                spec.proxyType(),
                spec.proxyHost(),
                spec.proxyPort(),
                spec.proxyUsername(),
                spec.proxyPassword(),
                spec.dimensions(),
                spec.description(),
                spec.enabled());
    }

    private void register(ModelSpec spec) {
        try {
            if ("embedding".equals(spec.kind())) {
                embeddingModels.put(spec.modelId(), buildEmbeddingModel(spec));
                return;
            }
            if (!"chat".equals(spec.kind())) {
                throw new IllegalStateException(
                        "Unsupported model kind for registration: " + spec.kind());
            }
            if ("local".equals(spec.type())) {
                Class<?> clazz = Class.forName(spec.className());
                Object instance = clazz.getDeclaredConstructor().newInstance();
                if (!(instance instanceof Model model)) {
                    throw new IllegalStateException(
                            "Configured class is not a agentscope model: " + spec.className());
                }
                ModelRegistry.register(spec.modelId(), model);
                return;
            }
            if ("provider".equals(spec.type())) {
                if (!spec.modelFactoryClass().isBlank()) {
                    registerProviderFactory(spec);
                    return;
                }
                if (shouldUseOpenAIChatModel(spec)) {
                    ModelRegistry.register(spec.modelId(), buildOpenAIModel(spec));
                    return;
                }
                ModelRegistry.register(
                        spec.modelId(), ModelRegistry.resolve(resolveModelRef(spec)));
                return;
            }
            throw new IllegalStateException(
                    "Unsupported model type for registration: " + spec.type());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to register model " + spec.modelId(), e);
        }
    }

    private EmbeddingModel buildEmbeddingModel(ModelSpec spec) {
        String provider = safe(spec.provider()).toLowerCase();
        String modelName = resolveModelName(spec);
        int dimensions = resolveDimensions(spec);
        ExecutionConfig executionConfig = buildExecutionConfig(spec);
        if (OPENAI_PROVIDERS.contains(provider)) {
            OpenAITextEmbedding.Builder builder =
                    OpenAITextEmbedding.builder()
                            .modelName(modelName)
                            .dimensions(dimensions)
                            .executionConfig(executionConfig);
            String apiKey = resolveApiKey(spec, "openai");
            if (!apiKey.isBlank()) {
                builder.apiKey(apiKey);
            }
            if (!safe(spec.baseUrl()).isBlank()) {
                builder.baseUrl(spec.baseUrl());
            }
            return builder.build();
        }
        if ("dashscope".equals(provider) || "qwen".equals(provider)) {
            DashScopeTextEmbedding.Builder builder =
                    DashScopeTextEmbedding.builder()
                            .modelName(modelName)
                            .dimensions(dimensions)
                            .executionConfig(executionConfig);
            String apiKey = resolveApiKey(spec, "dashscope");
            if (!apiKey.isBlank()) {
                builder.apiKey(apiKey);
            }
            if (!safe(spec.baseUrl()).isBlank()) {
                builder.baseUrl(spec.baseUrl());
            }
            return builder.build();
        }
        if ("ollama".equals(provider)) {
            OllamaTextEmbedding.Builder builder =
                    OllamaTextEmbedding.builder()
                            .modelName(modelName)
                            .dimensions(dimensions)
                            .executionConfig(executionConfig);
            if (!safe(spec.baseUrl()).isBlank()) {
                builder.baseUrl(spec.baseUrl());
            }
            return builder.build();
        }
        throw new IllegalStateException(
                "Unsupported embedding provider for " + spec.modelId() + ": " + spec.provider());
    }

    private int resolveDimensions(ModelSpec spec) {
        if (spec.dimensions() != null) {
            if (spec.dimensions() <= 0) {
                throw new IllegalArgumentException(
                        "dimensions must be positive for embedding model: " + spec.modelId());
            }
            return spec.dimensions();
        }
        return switch (safe(spec.provider()).toLowerCase()) {
            case "openai", "openai-compatible", "openai_compatible" -> 1536;
            case "dashscope", "qwen" -> 1024;
            case "ollama" -> -1;
            default -> 1024;
        };
    }

    private void registerProviderFactory(ModelSpec spec) {
        Object instance = instantiate(spec.modelFactoryClass(), "modelFactoryClass");
        if (instance instanceof Model model) {
            ModelRegistry.register(spec.modelId(), model);
            return;
        }
        if (instance instanceof ModelRegistry.ModelFactory factory) {
            ModelRegistry.register(spec.modelId(), factory.create(resolveModelRef(spec)));
            return;
        }
        throw new IllegalStateException(
                "modelFactoryClass must implement io.agentscope.core.model.Model or "
                        + "io.agentscope.core.model.ModelRegistry.ModelFactory: "
                        + spec.modelFactoryClass());
    }

    private Model buildOpenAIModel(ModelSpec spec) {
        String modelName = resolveModelName(spec);
        String apiKey = resolveApiKey(spec, "openai");
        String baseUrl = safe(spec.baseUrl());
        String endpointPath = safe(spec.endpointPath());
        Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter =
                resolveFormatter(spec.formatterClass());

        OpenAIChatModel.Builder builder = OpenAIChatModel.builder().modelName(modelName);
        if (!apiKey.isBlank()) {
            builder.apiKey(apiKey);
        }
        if (!baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        if (!endpointPath.isBlank()) {
            builder.endpointPath(endpointPath);
        }
        if (spec.stream() != null) {
            builder.stream(spec.stream());
        }
        if (formatter != null) {
            builder.formatter(formatter);
        }
        ProxyConfig proxy = buildProxyConfig(spec);
        if (proxy != null) {
            builder.proxy(proxy);
        }

        GenerateOptions options =
                buildGenerateOptions(spec, modelName, apiKey, baseUrl, endpointPath);
        builder.generateOptions(options);
        return builder.build();
    }

    private GenerateOptions buildGenerateOptions(
            ModelSpec spec, String modelName, String apiKey, String baseUrl, String endpointPath) {
        GenerateOptions.Builder options =
                GenerateOptions.builder()
                        .modelName(modelName)
                        .apiKey(apiKey.isBlank() ? null : apiKey)
                        .baseUrl(baseUrl.isBlank() ? null : baseUrl)
                        .endpointPath(endpointPath.isBlank() ? null : endpointPath)
                        .executionConfig(buildExecutionConfig(spec));

        if (spec.stream() != null) {
            options.stream(spec.stream());
        }
        if (spec.temperature() != null) {
            options.temperature(spec.temperature());
        }
        if (spec.topP() != null) {
            options.topP(spec.topP());
        }
        if (spec.maxTokens() != null) {
            options.maxTokens(spec.maxTokens());
        }
        if (spec.maxCompletionTokens() != null) {
            options.maxCompletionTokens(spec.maxCompletionTokens());
        }
        if (spec.frequencyPenalty() != null) {
            options.frequencyPenalty(spec.frequencyPenalty());
        }
        if (spec.presencePenalty() != null) {
            options.presencePenalty(spec.presencePenalty());
        }
        if (spec.thinkingBudget() != null) {
            options.thinkingBudget(spec.thinkingBudget());
        }
        if (!spec.reasoningEffort().isBlank()) {
            options.reasoningEffort(spec.reasoningEffort());
        }
        if (spec.topK() != null) {
            options.topK(spec.topK());
        }
        if (spec.seed() != null) {
            options.seed(spec.seed());
        }
        if (spec.cacheControl() != null) {
            options.cacheControl(spec.cacheControl());
        }
        if (spec.parallelToolCalls() != null) {
            options.parallelToolCalls(spec.parallelToolCalls());
        }
        spec.additionalHeaders().forEach(options::additionalHeader);
        spec.additionalBodyParams().forEach(options::additionalBodyParam);
        for (Map.Entry<String, String> entry : spec.additionalQueryParams().entrySet()) {
            options.additionalQueryParam(entry.getKey(), entry.getValue());
        }
        return options.build();
    }

    private ExecutionConfig buildExecutionConfig(ModelSpec spec) {
        ExecutionConfig.Builder builder = ExecutionConfig.builder();
        boolean configured = false;
        if (spec.executionTimeoutMs() != null) {
            builder.timeout(Duration.ofMillis(spec.executionTimeoutMs()));
            configured = true;
        }
        if (spec.executionMaxAttempts() != null) {
            builder.maxAttempts(spec.executionMaxAttempts());
            configured = true;
        }
        if (spec.executionInitialBackoffMs() != null) {
            builder.initialBackoff(Duration.ofMillis(spec.executionInitialBackoffMs()));
            configured = true;
        }
        if (spec.executionMaxBackoffMs() != null) {
            builder.maxBackoff(Duration.ofMillis(spec.executionMaxBackoffMs()));
            configured = true;
        }
        if (spec.executionBackoffMultiplier() != null) {
            builder.backoffMultiplier(spec.executionBackoffMultiplier());
            configured = true;
        }
        return configured ? builder.build() : null;
    }

    private ProxyConfig buildProxyConfig(ModelSpec spec) {
        if (safe(spec.proxyHost()).isBlank()) {
            return null;
        }
        if (spec.proxyPort() == null) {
            throw new IllegalStateException(
                    "proxyPort is required when proxyHost is configured: " + spec.modelId());
        }
        if (spec.proxyPort() <= 0 || spec.proxyPort() > 65535) {
            throw new IllegalArgumentException(
                    "proxyPort must be between 1 and 65535 for model: " + spec.modelId());
        }

        ProxyConfig.Builder proxy =
                ProxyConfig.builder()
                        .host(spec.proxyHost())
                        .port(spec.proxyPort())
                        .type(resolveProxyType(spec.proxyType()));
        if (!safe(spec.proxyUsername()).isBlank()) {
            proxy.username(spec.proxyUsername());
        }
        if (!safe(spec.proxyPassword()).isBlank()) {
            proxy.password(spec.proxyPassword());
        }
        return proxy.build();
    }

    private ProxyType resolveProxyType(String proxyType) {
        return switch (safe(proxyType).toLowerCase()) {
            case "", "http" -> ProxyType.HTTP;
            case "socks4" -> ProxyType.SOCKS4;
            case "socks5" -> ProxyType.SOCKS5;
            default -> throw new IllegalArgumentException("Unsupported proxyType: " + proxyType);
        };
    }

    private String resolveApiKey(ModelSpec spec, String provider) {
        String key = safe(spec.apiKey());
        if (!key.isBlank()) {
            return key;
        }
        String apiKeyEnv = safe(spec.apiKeyEnv());
        if (!apiKeyEnv.isBlank()) {
            String byEnv = System.getenv(apiKeyEnv);
            return byEnv == null ? "" : byEnv;
        }
        String env = defaultApiKeyEnv(provider);
        if (env == null) {
            return "";
        }
        String value = System.getenv(env);
        return value == null ? "" : value;
    }

    private String defaultApiKeyEnv(String provider) {
        return switch (provider == null ? "" : provider.toLowerCase()) {
            case "openai", "openai-compatible", "openai_compatible" -> "OPENAI_API_KEY";
            case "dashscope", "qwen" -> "DASHSCOPE_API_KEY";
            case "anthropic" -> "ANTHROPIC_API_KEY";
            case "gemini" -> "GEMINI_API_KEY";
            default -> "";
        };
    }

    @SuppressWarnings("unchecked")
    private Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> resolveFormatter(
            String formatterClass) {
        String name = safe(formatterClass);
        if (name.isBlank()) {
            return null;
        }
        try {
            Class<?> clazz = Class.forName(name);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            if (!(instance instanceof Formatter<?, ?, ?>)) {
                throw new IllegalStateException("formatterClass must implement Formatter: " + name);
            }
            return (Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest>) instance;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to instantiate formatterClass: " + name, e);
        }
    }

    private boolean shouldUseOpenAIChatModel(ModelSpec spec) {
        if (OPENAI_PROVIDERS.contains(spec.provider())) {
            if (!"openai".equals(spec.provider())) {
                return true;
            }
            if (!safe(spec.baseUrl()).isBlank()
                    || !safe(spec.endpointPath()).isBlank()
                    || !spec.formatterClass().isBlank()
                    || !safe(spec.apiKey()).isBlank()
                    || !safe(spec.apiKeyEnv()).isBlank()
                    || spec.stream() != null
                    || spec.temperature() != null
                    || spec.topP() != null
                    || spec.maxTokens() != null
                    || spec.maxCompletionTokens() != null
                    || spec.frequencyPenalty() != null
                    || spec.presencePenalty() != null
                    || spec.thinkingBudget() != null
                    || !safe(spec.reasoningEffort()).isBlank()
                    || spec.topK() != null
                    || spec.seed() != null
                    || spec.cacheControl() != null
                    || spec.parallelToolCalls() != null
                    || !spec.additionalHeaders().isEmpty()
                    || !spec.additionalBodyParams().isEmpty()
                    || !spec.additionalQueryParams().isEmpty()
                    || spec.executionTimeoutMs() != null
                    || spec.executionMaxAttempts() != null
                    || spec.executionInitialBackoffMs() != null
                    || spec.executionMaxBackoffMs() != null
                    || spec.executionBackoffMultiplier() != null
                    || !safe(spec.proxyHost()).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private String resolveModelName(ModelSpec spec) {
        String model = safe(spec.model());
        if (model != null && !model.isBlank()) {
            return model;
        }
        String modelId = safe(spec.modelId());
        int idx = modelId.indexOf(":");
        if (idx >= 0 && idx + 1 < modelId.length()) {
            return modelId.substring(idx + 1);
        }
        return modelId;
    }

    private String resolveModelRef(ModelSpec spec) {
        String direct = resolve(safe(spec.model()));
        if (direct.contains(":")) {
            return direct;
        }
        return safe(spec.provider()) + ":" + resolveModelName(spec);
    }

    private ModelSpec normalize(ModelSpec spec) {
        return new ModelSpec(
                resolve(spec.modelId()),
                resolve(spec.kind()),
                spec.type(),
                resolve(spec.provider()),
                resolve(spec.model()),
                resolve(spec.className()),
                resolve(spec.modelFactoryClass()),
                resolve(spec.apiKey()),
                resolve(spec.apiKeyEnv()),
                resolve(spec.baseUrl()),
                resolve(spec.endpointPath()),
                resolve(spec.formatterClass()),
                spec.stream(),
                spec.temperature(),
                spec.topP(),
                spec.maxTokens(),
                spec.maxCompletionTokens(),
                spec.frequencyPenalty(),
                spec.presencePenalty(),
                spec.thinkingBudget(),
                resolve(spec.reasoningEffort()),
                spec.topK(),
                spec.seed(),
                spec.cacheControl(),
                spec.parallelToolCalls(),
                resolveMapString(spec.additionalHeaders()),
                resolveMapObject(spec.additionalBodyParams()),
                resolveMapString(spec.additionalQueryParams()),
                spec.executionTimeoutMs(),
                spec.executionMaxAttempts(),
                spec.executionInitialBackoffMs(),
                spec.executionMaxBackoffMs(),
                spec.executionBackoffMultiplier(),
                resolve(spec.proxyType()),
                resolve(spec.proxyHost()),
                spec.proxyPort(),
                resolve(spec.proxyUsername()),
                resolve(spec.proxyPassword()),
                spec.dimensions(),
                resolve(spec.description()),
                spec.enabled());
    }

    private void validateSpec(ModelSpec spec) {
        if (spec.modelId() == null || spec.modelId().isBlank()) {
            throw new IllegalStateException("Model id cannot be blank");
        }
        if ("local".equals(spec.type())
                && (spec.className() == null || spec.className().isBlank())) {
            throw new IllegalStateException(
                    "Model className cannot be blank for local type: " + spec.modelId());
        }
        if (!"local".equals(spec.type()) && !"provider".equals(spec.type())) {
            throw new IllegalStateException(
                    "Unsupported model type for " + spec.modelId() + ": " + spec.type());
        }
        if (!"chat".equals(spec.kind()) && !"embedding".equals(spec.kind())) {
            throw new IllegalStateException(
                    "Unsupported model kind for " + spec.modelId() + ": " + spec.kind());
        }
        if ("embedding".equals(spec.kind()) && !"provider".equals(spec.type())) {
            throw new IllegalStateException(
                    "Embedding model must use provider type: " + spec.modelId());
        }
    }

    private void persist() {
        List<ModelConfig> list =
                models.values().stream()
                        .sorted((a, b) -> a.modelId().compareToIgnoreCase(b.modelId()))
                        .map(this::toConfig)
                        .collect(Collectors.toList());
        configStore.write(PlatformConfigStore.ConfigFile.MODELS, new ModelConfigRoot(list));
    }

    private String resolve(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            return environment.resolveRequiredPlaceholders(value);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private Map<String, String> resolveMapString(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = safe(entry.getKey());
            if (key.isBlank()) {
                continue;
            }
            resolved.put(key, safe(resolve(entry.getValue())));
        }
        return Map.copyOf(resolved);
    }

    private Map<String, Object> resolveMapObject(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = safe(entry.getKey());
            if (key.isBlank()) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof String s) {
                resolved.put(key, resolve(s));
            } else {
                resolved.put(key, value);
            }
        }
        return Map.copyOf(resolved);
    }

    private Object instantiate(String className, String role) {
        String resolved = resolve(className);
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalStateException(role + " cannot be blank");
        }
        try {
            Class<?> clazz = Class.forName(resolved);
            return clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate " + role + ": " + resolved, e);
        }
    }

    public record ModelConfigRoot(List<ModelConfig> models) {
        public ModelConfigRoot {
            models = models == null ? List.of() : List.copyOf(models);
        }
    }

    public record ModelConfig(
            String modelId,
            String kind,
            String type,
            String provider,
            String model,
            String className,
            String modelFactoryClass,
            String apiKey,
            String apiKeyEnv,
            String baseUrl,
            String endpointPath,
            String formatterClass,
            Boolean stream,
            Double temperature,
            Double topP,
            Integer maxTokens,
            Integer maxCompletionTokens,
            Double frequencyPenalty,
            Double presencePenalty,
            Integer thinkingBudget,
            String reasoningEffort,
            Integer topK,
            Long seed,
            Boolean cacheControl,
            Boolean parallelToolCalls,
            Map<String, String> additionalHeaders,
            Map<String, Object> additionalBodyParams,
            Map<String, String> additionalQueryParams,
            Long executionTimeoutMs,
            Integer executionMaxAttempts,
            Long executionInitialBackoffMs,
            Long executionMaxBackoffMs,
            Double executionBackoffMultiplier,
            String proxyType,
            String proxyHost,
            Integer proxyPort,
            String proxyUsername,
            String proxyPassword,
            Integer dimensions,
            String description,
            boolean enabled) {}
}
