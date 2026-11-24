package gg.modl.minecraft.core.impl.menus.base;

import org.jspecify.annotations.Nullable;

final class BrowserRow<T> {
    private static final String PLACEHOLDER_KEY = "empty-placeholder";

    private final @Nullable T item;

    private BrowserRow(@Nullable T item) {
        this.item = item;
    }

    static <T> BrowserRow<T> of(T item) {
        return new BrowserRow<>(item);
    }

    static <T> BrowserRow<T> placeholder() {
        return new BrowserRow<>(null);
    }

    @Nullable T item() {
        return item;
    }

    @Override
    public String toString() {
        return item == null ? PLACEHOLDER_KEY : item.toString();
    }
}
