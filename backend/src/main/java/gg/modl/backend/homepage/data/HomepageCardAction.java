package gg.modl.backend.homepage.data;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

public enum HomepageCardAction {
    LINK("url"),
    CATEGORY("category_dropdown");

    private final String storedType;

    HomepageCardAction(String storedType) {
        this.storedType = storedType;
    }

    public String storedType() {
        return storedType;
    }

    public static HomepageCardAction ofStored(@Nullable String storedType) {
        return Arrays.stream(values()).filter(action -> action.storedType.equals(storedType)).findFirst().orElse(LINK);
    }
}
