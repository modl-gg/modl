package gg.modl.backend.ai.probe;

import gg.modl.backend.ai.model.AiCompletionRequest;
import gg.modl.backend.ai.service.ChatModerationPromptBuilder;
import gg.modl.backend.ai.service.ChatModerationSchema;
import gg.modl.backend.settings.data.AIModerationSettings.AIPunishmentConfig;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiProbeSample {
    static final String SAMPLE_TYPE_ID = "1";
    private static final Instant SAMPLE_TIME = Instant.EPOCH;
    private static final AIPunishmentConfig SAMPLE_RULE = AIPunishmentConfig.builder()
        .id(SAMPLE_TYPE_ID)
        .name("Chat Abuse")
        .aiDescription("Spamming, excessive profanity or abusive language")
        .enabled(true)
        .build();

    private final ChatModerationPromptBuilder promptBuilder;

    public AiCompletionRequest request(int maxOutputTokens) {
        Ticket sample = Ticket.builder()
            .id("probe")
            .type(TicketCategory.CHAT)
            .reportedPlayer("Steve")
            .chatMessages(List.of(
                new Ticket.ChatMessage("Good game everyone, see you tomorrow", SAMPLE_TIME, "Steve"),
                new Ticket.ChatMessage("Thanks, you too", SAMPLE_TIME, "Alex")))
            .build();
        ChatModerationPromptBuilder.ModerationPrompt prompt = promptBuilder.buildModerationPrompt(sample, List.of(SAMPLE_RULE));
        return new AiCompletionRequest(prompt.systemInstruction(), prompt.userContent(),
            ChatModerationSchema.forTypes(prompt.typeIds()), maxOutputTokens);
    }

    public List<String> typeIds() {
        return List.of(SAMPLE_TYPE_ID);
    }
}
