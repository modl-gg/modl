package gg.modl.backend.ai.provider;

import static gg.modl.backend.ai.model.AiRequestShape.OutputMode.JSON_OBJECT;
import static gg.modl.backend.ai.model.AiRequestShape.OutputMode.PROMPT_ONLY;
import static gg.modl.backend.ai.model.AiRequestShape.OutputMode.SCHEMA;
import static gg.modl.backend.ai.model.AiRequestShape.TokenField.MAX_COMPLETION_TOKENS;
import static gg.modl.backend.ai.model.AiRequestShape.TokenField.MAX_TOKENS;

import gg.modl.backend.ai.model.AiProtocol;
import gg.modl.backend.ai.model.AiProviderDefinition;
import gg.modl.backend.ai.model.AiProviderDefinition.Endpoint;
import gg.modl.backend.ai.model.AiProviderDefinition.Group;
import gg.modl.backend.ai.model.AiProviderDefinition.KeyPolicy;
import gg.modl.backend.ai.model.AiProviderDefinition.Region;
import gg.modl.backend.ai.model.AiRequestShape;
import gg.modl.backend.ai.model.AiRequestShape.OutputMode;
import gg.modl.backend.ai.model.AiRequestShape.TokenField;
import gg.modl.backend.ai.model.OpenAiChatDialect;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class AiProviderCatalog {
    public static final String GEMINI = "gemini";

    private static final List<AiProviderDefinition> PROVIDERS = List.of(
        fixed("openai", "OpenAI", Group.CLOUD, AiProtocol.OPENAI_CHAT,
            List.of(global("https://api.openai.com/v1")), "https://platform.openai.com/api-keys",
            List.of(SCHEMA, JSON_OBJECT, PROMPT_ONLY), MAX_COMPLETION_TOKENS, true, OpenAiChatDialect.STANDARD),
        fixed("anthropic", "Anthropic", Group.CLOUD, AiProtocol.ANTHROPIC_MESSAGES,
            List.of(global("https://api.anthropic.com/v1")), "https://platform.claude.com/settings/keys",
            List.of(SCHEMA, PROMPT_ONLY), MAX_TOKENS, true, OpenAiChatDialect.STANDARD),
        fixed(GEMINI, "Google Gemini", Group.CLOUD, AiProtocol.GEMINI_GENERATE,
            List.of(global("https://generativelanguage.googleapis.com/v1beta")), "https://aistudio.google.com/apikey",
            List.of(SCHEMA, JSON_OBJECT, PROMPT_ONLY), MAX_TOKENS, false, OpenAiChatDialect.STANDARD),
        fixed("deepseek", "DeepSeek", Group.CLOUD, AiProtocol.OPENAI_CHAT,
            List.of(global("https://api.deepseek.com")), "https://platform.deepseek.com/api_keys",
            List.of(JSON_OBJECT, PROMPT_ONLY), MAX_TOKENS, true, OpenAiChatDialect.STANDARD),
        new AiProviderDefinition("qwen", "Qwen (Alibaba Model Studio)", Group.CLOUD, AiProtocol.OPENAI_CHAT,
            List.of(
                endpoint(Region.SINGAPORE, "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"),
                endpoint(Region.CHINA, "https://dashscope.aliyuncs.com/compatible-mode/v1"),
                endpoint(Region.US, "https://dashscope-us.aliyuncs.com/compatible-mode/v1")),
            true, KeyPolicy.REQUIRED, URI.create("https://modelstudio.console.alibabacloud.com/"),
            shape(SCHEMA, MAX_TOKENS, false), List.of(SCHEMA, JSON_OBJECT, PROMPT_ONLY), OpenAiChatDialect.STANDARD),
        fixed("moonshot", "Moonshot AI (Kimi)", Group.CLOUD, AiProtocol.OPENAI_CHAT,
            List.of(global("https://api.moonshot.ai/v1"), endpoint(Region.CHINA, "https://api.moonshot.cn/v1")),
            "https://platform.kimi.ai/console/api-keys",
            List.of(SCHEMA, JSON_OBJECT, PROMPT_ONLY), MAX_COMPLETION_TOKENS, true, OpenAiChatDialect.STANDARD),
        fixed("zhipu", "Zhipu GLM", Group.CLOUD, AiProtocol.OPENAI_CHAT,
            List.of(global("https://api.z.ai/api/paas/v4"), endpoint(Region.CHINA, "https://open.bigmodel.cn/api/paas/v4")),
            "https://z.ai/manage-apikey/apikey-list",
            List.of(JSON_OBJECT, PROMPT_ONLY), MAX_TOKENS, false, OpenAiChatDialect.STANDARD),
        fixed("minimax", "MiniMax", Group.CLOUD, AiProtocol.OPENAI_CHAT,
            List.of(global("https://api.minimax.io/v1"), endpoint(Region.CHINA, "https://api.minimaxi.com/v1")),
            "https://platform.minimax.io/",
            List.of(PROMPT_ONLY), MAX_COMPLETION_TOKENS, true, new OpenAiChatDialect(Map.of("reasoning_split", true), false, null)),
        fixed("openrouter", "OpenRouter", Group.GATEWAY, AiProtocol.OPENAI_CHAT,
            List.of(global("https://openrouter.ai/api/v1")), "https://openrouter.ai/settings/keys",
            List.of(SCHEMA, JSON_OBJECT, PROMPT_ONLY), MAX_COMPLETION_TOKENS, false, new OpenAiChatDialect(Map.of(), true, "/key")),
        local("ollama", "Ollama", Group.LOCAL, List.of(global("http://localhost:11434/v1")), List.of(SCHEMA, JSON_OBJECT, PROMPT_ONLY)),
        local("lm-studio", "LM Studio", Group.LOCAL, List.of(global("http://localhost:1234/v1")), List.of(SCHEMA, PROMPT_ONLY)),
        local("custom", "OpenAI-compatible", Group.CUSTOM, List.of(), List.of(SCHEMA, JSON_OBJECT, PROMPT_ONLY))
    );

    private final Map<String, AiProviderDefinition> byId;

    public AiProviderCatalog() {
        Map<String, AiProviderDefinition> indexed = new LinkedHashMap<>();
        PROVIDERS.forEach(provider -> indexed.put(provider.id(), provider));
        this.byId = Map.copyOf(indexed);
    }

    public List<AiProviderDefinition> all() {
        return PROVIDERS;
    }

    public Optional<AiProviderDefinition> find(@Nullable String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    private static AiProviderDefinition fixed(String id, String name, Group group, AiProtocol protocol, List<Endpoint> endpoints,
                                              String keyPage, List<OutputMode> ladder, TokenField tokenField, boolean effortHint,
                                              OpenAiChatDialect dialect) {
        return new AiProviderDefinition(id, name, group, protocol, endpoints, false, KeyPolicy.REQUIRED, URI.create(keyPage),
            shape(ladder.getFirst(), tokenField, effortHint), ladder, dialect);
    }

    private static AiProviderDefinition local(String id, String name, Group group, List<Endpoint> endpoints, List<OutputMode> ladder) {
        return new AiProviderDefinition(id, name, group, AiProtocol.OPENAI_CHAT, endpoints, true, KeyPolicy.OPTIONAL, null,
            shape(ladder.getFirst(), MAX_TOKENS, false), ladder, OpenAiChatDialect.STANDARD);
    }

    private static AiRequestShape shape(OutputMode mode, TokenField tokenField, boolean effortHint) {
        return new AiRequestShape(mode, tokenField, effortHint);
    }

    private static Endpoint global(String url) {
        return endpoint(Region.GLOBAL, url);
    }

    private static Endpoint endpoint(Region region, String url) {
        return new Endpoint(region, URI.create(url));
    }
}
