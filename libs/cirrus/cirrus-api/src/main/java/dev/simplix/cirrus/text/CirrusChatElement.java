package dev.simplix.cirrus.text;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import lombok.NonNull;
import net.kyori.adventure.text.Component;

public final class CirrusChatElement {

    private static final CirrusChatElement EMPTY = new CirrusChatElement(Component.empty(), "");

    private final Component component;
    private final String legacyText;

    private CirrusChatElement(@NonNull Component component, @NonNull String legacyText) {
        this.component = component;
        this.legacyText = legacyText;
    }

    public static CirrusChatElement ofLegacyText(@Nullable String legacyText) {
        if (legacyText == null || legacyText.isEmpty()) {
            return EMPTY;
        }
        String colorized = LegacyColorCodes.translateAmpersandCodes(legacyText);
        return new CirrusChatElement(LegacyText.deserialize(colorized), colorized);
    }

    public static CirrusChatElement empty() {
        return EMPTY;
    }

    public Component asComponent() {
        return component;
    }

    public String asLegacyText() {
        return legacyText;
    }

    public boolean isEmpty() {
        return legacyText.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CirrusChatElement that = (CirrusChatElement) o;
        return Objects.equals(component, that.component);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(component);
    }

    @Override
    public String toString() {
        return legacyText;
    }
}
