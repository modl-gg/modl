package gg.modl.backend.status;

import java.time.Instant;
import java.util.List;

public record SupportReport(List<StatusFact> facts, Instant generatedAt) {
    static final String TITLE = "modl support report";

    public SupportReport {
        facts = List.copyOf(facts);
    }

    public String render() {
        StringBuilder text = new StringBuilder(TITLE).append('\n').append("Generated: ").append(generatedAt).append('\n');
        StatusSection current = null;
        for (StatusFact fact : facts) {
            if (fact.item().section() != current) {
                current = fact.item().section();
                text.append('\n').append('[').append(current.title()).append(']').append('\n');
            }
            text.append(fact.item().label()).append(": ").append(fact.value()).append(marker(fact.level())).append('\n');
        }
        return text.toString();
    }

    private static String marker(StatusLevel level) {
        return switch (level) {
            case OK -> "";
            case WARNING -> " (warning)";
            case PROBLEM -> " (problem)";
        };
    }
}
