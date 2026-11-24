package gg.modl.backend.ai.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.ai.model.AiModelInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChatModelFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "text-embedding-3-large", "whisper-1", "tts-1-hd", "gpt-4o-realtime-preview", "gemini-2.0-flash-live", "dall-e-3",
        "imagen-4.0", "veo-3", "sora-2", "omni-moderation-latest", "bge-rerank", "llama-guard-4", "qwen-vl-ocr", "cogview-4",
        "wan2.1-t2v", "computer-use-preview", "gpt-4o-audio-preview", "paraformer-asr"
    })
    void nonChatModelsAreFlagged(String id) {
        assertThat(ChatModelFilter.classify(model(id, true)).chatCapable()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-5-mini", "claude-sonnet", "gemini-pro-vision", "deepseek-chat", "glm-4.6", "kimi-k3", "swan-chat", "liveliness"})
    void chatModelsStay(String id) {
        assertThat(ChatModelFilter.classify(model(id, true)).chatCapable()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-5-mini"})
    void metadataThatRulesOutChatWins(String id) {
        assertThat(ChatModelFilter.classify(model(id, false)).chatCapable()).isFalse();
    }

    private static AiModelInfo model(String id, boolean chatCapable) {
        return new AiModelInfo(id, null, chatCapable, false, null, null, null);
    }
}
