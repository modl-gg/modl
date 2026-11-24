package gg.modl.backend.theme.service;

import gg.modl.backend.theme.data.PublicSiteTheme;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

final class PublicThemeChanges {
    private static final List<Field> FIELDS = List.of(
        new Field("primary color", PublicSiteTheme::primaryColor),
        new Field("accent color", PublicSiteTheme::accentColor),
        new Field("background color", PublicSiteTheme::backgroundColor),
        new Field("background image", PublicSiteTheme::backgroundImageUrl),
        new Field("font", PublicSiteTheme::font),
        new Field("corner radius", PublicSiteTheme::radius),
        new Field("default color scheme", PublicSiteTheme::colorScheme));

    private PublicThemeChanges() {
    }

    static List<String> between(PublicSiteTheme previous, PublicSiteTheme next) {
        return FIELDS.stream()
            .filter(field -> !Objects.equals(field.value().apply(previous), field.value().apply(next)))
            .map(Field::label)
            .toList();
    }

    private record Field(String label, Function<PublicSiteTheme, Object> value) {
    }
}
