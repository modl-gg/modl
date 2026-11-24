package gg.modl.backend.webhook;

import gg.modl.backend.settings.data.WebhookSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record DiscordEmbed(DiscordEmbedLimits.Embed content, int color) {
    static final int DEFAULT_COLOR = 3447003;
    private static final int HEX_RADIX = 16;
    private static final String VARIABLE_START = "{{";
    private static final String VARIABLE_END = "}}";

    static DiscordEmbed fromTemplate(WebhookSettings.EmbedTemplate template, Map<String, String> variables, int budget) {
        List<DiscordEmbedLimits.Field> fields = template.getFields() == null ? List.of() : template.getFields().stream()
            .map(field -> new DiscordEmbedLimits.Field(
                substitute(field.getName(), variables), substitute(field.getValue(), variables), field.isInline()))
            .toList();
        return new DiscordEmbed(DiscordEmbedLimits.fit(
            substitute(template.getTitle(), variables), substitute(template.getDescription(), variables), fields, budget),
            colorOf(template.getColor()));
    }

    static DiscordEmbed plain(String title, String description, int color) {
        return new DiscordEmbed(DiscordEmbedLimits.fit(title, description, List.of()), color);
    }

    int length() {
        return content.length();
    }

    Map<String, Object> toPayload() {
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", content.title());
        embed.put("description", content.description());
        embed.put("color", color);
        if (!content.fields().isEmpty()) {
            embed.put("fields", content.fields().stream().map(DiscordEmbed::fieldPayload).toList());
        }
        return embed;
    }

    private static Map<String, Object> fieldPayload(DiscordEmbedLimits.Field field) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", field.name());
        payload.put("value", field.value());
        payload.put("inline", field.inline());
        return payload;
    }

    private static String substitute(String text, Map<String, String> variables) {
        if (text == null) {
            return "";
        }
        String result = text;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            result = result.replace(VARIABLE_START + variable.getKey() + VARIABLE_END,
                variable.getValue() != null ? variable.getValue() : "");
        }
        return result;
    }

    private static int colorOf(String hexColor) {
        if (hexColor == null || hexColor.isBlank()) {
            return DEFAULT_COLOR;
        }
        try {
            return Integer.parseInt(hexColor.startsWith("#") ? hexColor.substring(1) : hexColor, HEX_RADIX);
        } catch (NumberFormatException notHex) {
            return DEFAULT_COLOR;
        }
    }
}
