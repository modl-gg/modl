package gg.modl.minecraft.core.imports;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum ScopedPunishmentChoice {
    WIDEN("widen", "SCOPED_PUNISHMENT_CHOICE_WIDEN"),
    SKIP("skip", "SCOPED_PUNISHMENT_CHOICE_SKIP");

    private final String word;
    private final String wireName;

    static Optional<ScopedPunishmentChoice> parse(@Nullable String word) {
        for (ScopedPunishmentChoice choice : values()) {
            if (choice.word.equalsIgnoreCase(word)) return Optional.of(choice);
        }
        return Optional.empty();
    }

    static String wordOf(String wireName) {
        for (ScopedPunishmentChoice choice : values()) {
            if (choice.wireName.equals(wireName)) return choice.word;
        }
        return "-";
    }
}
