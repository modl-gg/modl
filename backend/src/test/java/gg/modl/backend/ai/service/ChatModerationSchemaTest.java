package gg.modl.backend.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.ai.model.AiOutputSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ChatModerationSchemaTest {

    @Test
    void theSchemaIsFlatStrictAndEnumeratesTheOfferedTypes() {
        AiOutputSchema schema = ChatModerationSchema.forTypes(List.of("6", "7"));

        assertThat(schema.name()).isEqualTo("chat_moderation");
        assertThat(schema.schema()).containsEntry("type", "object").containsEntry("additionalProperties", false);
        assertThat(schema.schema().get("required")).isEqualTo(ChatModerationSchema.FIELDS);
        Map<?, ?> properties = (Map<?, ?>) schema.schema().get("properties");
        assertThat(properties.keySet().stream().map(String::valueOf).toList()).containsExactlyElementsOf(ChatModerationSchema.FIELDS);
        assertThat(((Map<?, ?>) properties.get("punishmentTypeId")).get("enum")).isEqualTo(List.of("6", "7", "none"));
    }

    @Test
    void theSchemaAvoidsKeywordsThatStrictProvidersReject() {
        String json = JsonMapper.shared().writeValueAsString(ChatModerationSchema.forTypes(List.of("6")).schema());

        assertThat(json).doesNotContain("anyOf", "oneOf", "allOf", "nullable", "minimum", "maximum", "minLength", "maxLength",
            "pattern", "format", "$ref", "\"null\"");
    }
}
