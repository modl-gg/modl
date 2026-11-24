package gg.modl.backend.ai.service;

import gg.modl.backend.ai.data.DefaultPrompts;
import gg.modl.backend.settings.data.AIModerationSettings.AIPunishmentConfig;
import gg.modl.backend.ticket.data.Ticket;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ChatModerationPromptBuilder {
    static final int MAX_CHAT_CHARACTERS = 32_000;
    private static final String REPORTED_PLAYER_REFERENCE = "the reported player identified in the untrusted chat data";
    private static final SecureRandom NONCE_RANDOM = new SecureRandom();
    private static final JsonMapper JSON = JsonMapper.shared();

    public ModerationPrompt buildModerationPrompt(Ticket ticket, List<AIPunishmentConfig> offeredTypes) {
        String nonce = generateNonce();
        String beginMarker = "===BEGIN_UNTRUSTED_CHAT_DATA:" + nonce + "===";
        String endMarker = "===END_UNTRUSTED_CHAT_DATA:" + nonce + "===";
        List<AIPunishmentConfig> types = offeredTypes.stream()
            .sorted(Comparator.comparingInt(config -> Integer.parseInt(config.getId())))
            .toList();
        List<String> typeIds = types.stream().map(AIPunishmentConfig::getId).toList();

        String userContent = beginMarker + "\n" + chatPayload(ticket) + "\n" + endMarker;
        String systemInstruction = DefaultPrompts.MINECRAFT
            .replace("{{REPORTED_PLAYER}}", REPORTED_PLAYER_REFERENCE)
            .replace("{{PUNISHMENT_TYPES}}", formatPunishmentTypes(types))
            .replace("{{CHAT_LOG}}", "")
            + "\n\n"
            + DefaultPrompts.UNTRUSTED_DATA_DIRECTIVE.formatted(beginMarker, endMarker)
            + "\n\n"
            + DefaultPrompts.OUTPUT_FORMAT.formatted(typeIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(", ")));

        return new ModerationPrompt(systemInstruction, userContent, typeIds);
    }

    private static String chatPayload(Ticket ticket) {
        Deque<Map<String, Object>> kept = new ArrayDeque<>();
        int budget = MAX_CHAT_CHARACTERS - JSON.writeValueAsString(payload(ticket, List.of())).length();
        List<Ticket.ChatMessage> messages = ticket.getChatMessages() == null ? List.of() : ticket.getChatMessages();
        for (int index = messages.size() - 1; index >= 0; index--) {
            Map<String, Object> entry = message(messages.get(index));
            int cost = JSON.writeValueAsString(entry).length() + 1;
            if (cost > budget) {
                break;
            }
            budget -= cost;
            kept.addFirst(entry);
        }
        return JSON.writeValueAsString(payload(ticket, List.copyOf(kept)));
    }

    private static Map<String, Object> payload(Ticket ticket, List<Map<String, Object>> messages) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportedPlayer", ticket.getReportedPlayer());
        payload.put("messages", messages);
        return payload;
    }

    private static Map<String, Object> message(Ticket.ChatMessage message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("sender", message.getSender());
        entry.put("content", message.getContent());
        return entry;
    }

    private static String generateNonce() {
        byte[] bytes = new byte[16];
        NONCE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String formatPunishmentTypes(List<AIPunishmentConfig> types) {
        return types.stream()
            .map(config -> {
                String description = config.getAiDescription();
                return "%s: (%s) %s".formatted(config.getId(), config.getName(),
                    description != null && !description.isBlank() ? description : config.getName());
            })
            .collect(Collectors.joining("\n"));
    }

    public record ModerationPrompt(String systemInstruction, String userContent, List<String> typeIds) {
        public ModerationPrompt {
            typeIds = List.copyOf(typeIds);
        }
    }
}
