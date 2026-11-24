package gg.modl.backend.theme.data;

import java.util.Objects;

public record PublicSiteTheme(
    String primaryColor,
    String accentColor,
    String backgroundColor,
    String backgroundImageUrl,
    ThemeFont font,
    ThemeRadius radius,
    ThemeColorScheme colorScheme
) {
    public static final PublicSiteTheme DEFAULT =
        new PublicSiteTheme("", "", "", "", ThemeFont.SYSTEM, ThemeRadius.MEDIUM, ThemeColorScheme.SYSTEM);

    public PublicSiteTheme {
        primaryColor = Objects.requireNonNullElse(primaryColor, "");
        accentColor = Objects.requireNonNullElse(accentColor, "");
        backgroundColor = Objects.requireNonNullElse(backgroundColor, "");
        backgroundImageUrl = Objects.requireNonNullElse(backgroundImageUrl, "");
        font = Objects.requireNonNullElse(font, ThemeFont.SYSTEM);
        radius = Objects.requireNonNullElse(radius, ThemeRadius.MEDIUM);
        colorScheme = Objects.requireNonNullElse(colorScheme, ThemeColorScheme.SYSTEM);
    }
}
