package gg.modl.backend.ai.catalog;

import gg.modl.backend.ai.model.AiModelInfo;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class ChatModelFilter {
    private static final List<List<String>> NON_CHAT_KEYWORDS = Arrays.stream(new String[] {
        "embed", "embedding", "whisper", "tts", "transcribe", "asr", "speech", "audio", "realtime", "live", "image", "dall-e",
        "imagen", "veo", "sora", "video", "moderation", "rerank", "guard", "ocr", "robotics", "computer-use", "cogview",
        "cogvideo", "wan"
    }).map(keyword -> List.of(keyword.split("-"))).toList();

    private ChatModelFilter() {
    }

    public static AiModelInfo classify(AiModelInfo model) {
        return model.withChatCapable(model.chatCapable() && !excluded(model.id()));
    }

    static boolean excluded(String modelId) {
        List<String> tokens = Arrays.stream(modelId.toLowerCase(Locale.ROOT).split("[^a-z]+"))
            .filter(token -> !token.isEmpty())
            .toList();
        return NON_CHAT_KEYWORDS.stream().anyMatch(keyword -> containsRun(tokens, keyword));
    }

    private static boolean containsRun(List<String> tokens, List<String> keyword) {
        for (int start = 0; start + keyword.size() <= tokens.size(); start++) {
            if (tokens.subList(start, start + keyword.size()).equals(keyword)) {
                return true;
            }
        }
        return false;
    }
}
