package gg.modl.backend.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DiscordEmbedLimitsTest {
    @Test
    void valuesAreCutToWhatDiscordAcceptsInsteadOfDroppingTheNotification() {
        String reason = "🚀".repeat(2000);

        DiscordEmbedLimits.Embed embed = DiscordEmbedLimits.fit("x".repeat(300), "short",
            List.of(new DiscordEmbedLimits.Field("Reason", reason, false)));

        String value = embed.fields().getFirst().value();
        assertThat(value.codePointCount(0, value.length())).isEqualTo(1024);
        assertThat(value).endsWith("...");
        assertThat(embed.title()).hasSize(256);
        assertThat(embed.description()).isEqualTo("short");
    }

    @Test
    void theWholeEmbedStaysWithinDiscordsTotalAndTheCutIsVisible() {
        List<DiscordEmbedLimits.Field> fields = IntStream.range(0, 25)
            .mapToObj(index -> new DiscordEmbedLimits.Field("Field " + index, "v".repeat(1024), false))
            .toList();

        DiscordEmbedLimits.Embed embed = DiscordEmbedLimits.fit("title", "d".repeat(4096), fields);
        DiscordEmbedLimits.Embed again = DiscordEmbedLimits.fit("title", "d".repeat(4096), fields);

        assertThat(embed.length()).isLessThanOrEqualTo(DiscordEmbedLimits.TOTAL);
        assertThat(embed).isEqualTo(again);
        assertThat(embed.fields()).hasSize(2);
        assertThat(embed.fields().getFirst().value()).hasSize(1024).doesNotEndWith("...");
        assertThat(embed.fields().getLast().value()).endsWith("...");
    }

    @Test
    void fieldsBeyondDiscordsCountMarkTheLastKeptFieldAndBlankValuesAreSkipped() {
        List<DiscordEmbedLimits.Field> fields = new ArrayList<>();
        fields.add(new DiscordEmbedLimits.Field("Empty", " ", false));
        fields.addAll(Collections.nCopies(30, new DiscordEmbedLimits.Field("Name", "value", true)));

        DiscordEmbedLimits.Embed embed = DiscordEmbedLimits.fit("t", "d", fields);

        assertThat(embed.fields()).hasSize(25).noneMatch(field -> field.name().equals("Empty"));
        assertThat(embed.fields().getLast().value()).isEqualTo("va...");
        assertThat(embed.fields().getFirst().value()).isEqualTo("value");
    }
}
