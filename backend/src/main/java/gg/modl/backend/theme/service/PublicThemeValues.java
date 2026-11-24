package gg.modl.backend.theme.service;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.theme.data.PublicSiteTheme;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class PublicThemeValues {
    static final String COLOR_INVALID_CODE = "THEME_COLOR_INVALID";
    static final String BACKGROUND_IMAGE_INVALID_CODE = "THEME_BACKGROUND_IMAGE_INVALID";
    static final int MAX_IMAGE_URL_LENGTH = 2048;

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final String SECURE_SCHEME = "https";
    private static final String UNSAFE_URL_CHARACTERS = "\"'()\\<>";
    private static final String ROOT = "/";
    private static final String NETWORK_PATH = "//";
    private static final String COLOR_INVALID_MESSAGE = "Theme colors must be written as #rrggbb";
    private static final String BACKGROUND_IMAGE_INVALID_MESSAGE =
        "The background image must be an uploaded image or an https address";

    private PublicThemeValues() {
    }

    static PublicSiteTheme validated(PublicSiteTheme requested, Set<String> storageOrigins) {
        return new PublicSiteTheme(
            color(requested.primaryColor()),
            color(requested.accentColor()),
            color(requested.backgroundColor()),
            imageUrl(requested.backgroundImageUrl(), storageOrigins),
            requested.font(),
            requested.radius(),
            requested.colorScheme());
    }

    static boolean isSafeImageUrl(String value, Set<String> storageOrigins) {
        if (value.length() > MAX_IMAGE_URL_LENGTH
            || value.chars().anyMatch(character -> character <= ' ' || character == 0x7f || UNSAFE_URL_CHARACTERS.indexOf(character) >= 0)) {
            return false;
        }
        if (value.startsWith(ROOT)) {
            return !value.startsWith(NETWORK_PATH);
        }
        try {
            URI uri = new URI(value);
            return uri.getScheme() != null && uri.getHost() != null && !uri.getHost().isEmpty() && uri.getRawUserInfo() == null
                && (SECURE_SCHEME.equals(uri.getScheme().toLowerCase(Locale.ROOT)) || storageOrigins.contains(originOf(uri)));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static String originOf(URI uri) {
        String origin = uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT);
        return uri.getPort() < 0 ? origin : origin + ":" + uri.getPort();
    }

    private static String color(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (!HEX_COLOR.matcher(trimmed).matches()) {
            throw new ValidationException(COLOR_INVALID_MESSAGE, COLOR_INVALID_CODE);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String imageUrl(String value, Set<String> storageOrigins) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (!isSafeImageUrl(trimmed, storageOrigins)) {
            throw new ValidationException(BACKGROUND_IMAGE_INVALID_MESSAGE, BACKGROUND_IMAGE_INVALID_CODE);
        }
        return trimmed;
    }
}
