package gg.modl.minecraft.core.imports;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum IpHistoryChoice {
    KEEP("keep", "IMPORTED_IP_HISTORY_CHOICE_KEEP"),
    STANDARD("standard", "IMPORTED_IP_HISTORY_CHOICE_STANDARD");

    private final String word;
    private final String wireName;

    static Optional<IpHistoryChoice> parse(@Nullable String word) {
        for (IpHistoryChoice choice : values()) {
            if (choice.word.equalsIgnoreCase(word)) return Optional.of(choice);
        }
        return Optional.empty();
    }

    static String wordOf(String wireName) {
        for (IpHistoryChoice choice : values()) {
            if (choice.wireName.equals(wireName)) return choice.word;
        }
        return "-";
    }
}
