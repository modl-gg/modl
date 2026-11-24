package gg.modl.backend.theme.controller;

import static gg.modl.backend.settings.controller.SettingsMetaMapper.toSettingsMeta;

import gg.modl.backend.settings.service.VersionedSettings;
import gg.modl.backend.theme.data.CustomStylesheet;
import gg.modl.backend.theme.data.PublicSiteTheme;
import gg.modl.backend.theme.data.ThemeColorScheme;
import gg.modl.backend.theme.data.ThemeFont;
import gg.modl.backend.theme.data.ThemeRadius;
import gg.modl.proto.modl.v1.PublicColorScheme;
import gg.modl.proto.modl.v1.PublicCustomCss;
import gg.modl.proto.modl.v1.PublicCustomCssEnvelope;
import gg.modl.proto.modl.v1.PublicTheme;
import gg.modl.proto.modl.v1.PublicThemeEnvelope;
import gg.modl.proto.modl.v1.PublicThemeFont;
import gg.modl.proto.modl.v1.PublicThemeRadius;

public final class PublicThemeProtoMapper {
    private PublicThemeProtoMapper() {
    }

    public static PublicTheme toProto(PublicSiteTheme theme) {
        return PublicTheme.newBuilder()
            .setPrimaryColor(theme.primaryColor())
            .setAccentColor(theme.accentColor())
            .setBackgroundColor(theme.backgroundColor())
            .setBackgroundImageUrl(theme.backgroundImageUrl())
            .setFont(toProto(theme.font()))
            .setRadius(toProto(theme.radius()))
            .setColorScheme(toProto(theme.colorScheme()))
            .build();
    }

    static PublicSiteTheme fromProto(PublicTheme theme) {
        return new PublicSiteTheme(
            theme.getPrimaryColor(),
            theme.getAccentColor(),
            theme.getBackgroundColor(),
            theme.getBackgroundImageUrl(),
            fromProto(theme.getFont()),
            fromProto(theme.getRadius()),
            fromProto(theme.getColorScheme()));
    }

    static PublicThemeEnvelope toEnvelope(VersionedSettings<PublicSiteTheme> theme) {
        return PublicThemeEnvelope.newBuilder()
            .setData(toProto(theme.data()))
            .setMeta(toSettingsMeta(theme))
            .build();
    }

    static PublicCustomCssEnvelope toCssEnvelope(VersionedSettings<CustomStylesheet> stylesheet) {
        return PublicCustomCssEnvelope.newBuilder()
            .setData(PublicCustomCss.newBuilder().setCss(stylesheet.data().css()))
            .setMeta(toSettingsMeta(stylesheet))
            .build();
    }

    private static PublicThemeFont toProto(ThemeFont font) {
        return switch (font) {
            case SYSTEM -> PublicThemeFont.PUBLIC_THEME_FONT_SYSTEM;
            case INTER -> PublicThemeFont.PUBLIC_THEME_FONT_INTER;
            case NUNITO -> PublicThemeFont.PUBLIC_THEME_FONT_NUNITO;
            case LORA -> PublicThemeFont.PUBLIC_THEME_FONT_LORA;
            case SPACE_GROTESK -> PublicThemeFont.PUBLIC_THEME_FONT_SPACE_GROTESK;
        };
    }

    private static ThemeFont fromProto(PublicThemeFont font) {
        return switch (font) {
            case PUBLIC_THEME_FONT_INTER -> ThemeFont.INTER;
            case PUBLIC_THEME_FONT_NUNITO -> ThemeFont.NUNITO;
            case PUBLIC_THEME_FONT_LORA -> ThemeFont.LORA;
            case PUBLIC_THEME_FONT_SPACE_GROTESK -> ThemeFont.SPACE_GROTESK;
            case PUBLIC_THEME_FONT_SYSTEM, PUBLIC_THEME_FONT_UNSPECIFIED, UNRECOGNIZED -> ThemeFont.SYSTEM;
        };
    }

    private static PublicThemeRadius toProto(ThemeRadius radius) {
        return switch (radius) {
            case NONE -> PublicThemeRadius.PUBLIC_THEME_RADIUS_NONE;
            case SMALL -> PublicThemeRadius.PUBLIC_THEME_RADIUS_SMALL;
            case MEDIUM -> PublicThemeRadius.PUBLIC_THEME_RADIUS_MEDIUM;
            case LARGE -> PublicThemeRadius.PUBLIC_THEME_RADIUS_LARGE;
        };
    }

    private static ThemeRadius fromProto(PublicThemeRadius radius) {
        return switch (radius) {
            case PUBLIC_THEME_RADIUS_NONE -> ThemeRadius.NONE;
            case PUBLIC_THEME_RADIUS_SMALL -> ThemeRadius.SMALL;
            case PUBLIC_THEME_RADIUS_LARGE -> ThemeRadius.LARGE;
            case PUBLIC_THEME_RADIUS_MEDIUM, PUBLIC_THEME_RADIUS_UNSPECIFIED, UNRECOGNIZED -> ThemeRadius.MEDIUM;
        };
    }

    private static PublicColorScheme toProto(ThemeColorScheme scheme) {
        return switch (scheme) {
            case SYSTEM -> PublicColorScheme.PUBLIC_COLOR_SCHEME_SYSTEM;
            case LIGHT -> PublicColorScheme.PUBLIC_COLOR_SCHEME_LIGHT;
            case DARK -> PublicColorScheme.PUBLIC_COLOR_SCHEME_DARK;
        };
    }

    private static ThemeColorScheme fromProto(PublicColorScheme scheme) {
        return switch (scheme) {
            case PUBLIC_COLOR_SCHEME_LIGHT -> ThemeColorScheme.LIGHT;
            case PUBLIC_COLOR_SCHEME_DARK -> ThemeColorScheme.DARK;
            case PUBLIC_COLOR_SCHEME_SYSTEM, PUBLIC_COLOR_SCHEME_UNSPECIFIED, UNRECOGNIZED -> ThemeColorScheme.SYSTEM;
        };
    }
}
