package gg.modl.backend.homepage.service;

import gg.modl.backend.homepage.data.FooterLink;
import gg.modl.backend.homepage.data.HomepageLayout;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.validation.SafeUrls;
import java.util.List;

final class HomepageSettingsValues {
    static final String TEXT_INVALID_CODE = "HOMEPAGE_TEXT_INVALID";
    static final String LINK_URL_INVALID_CODE = "HOMEPAGE_LINK_URL_INVALID";
    static final String TOO_MANY_LINKS_CODE = "HOMEPAGE_TOO_MANY_LINKS";
    static final int MAX_TAGLINE_LENGTH = 160;
    static final int MAX_SERVER_ADDRESS_LENGTH = 255;
    static final int MAX_LINK_LABEL_LENGTH = 48;
    static final int MAX_LINK_URL_LENGTH = 2048;
    static final int MAX_FOOTER_LINKS = 8;

    private static final String TEXT_INVALID_MESSAGE = "Homepage text must be a single line within its length limit";
    private static final String LINK_URL_INVALID_MESSAGE = "Footer links must be http or https addresses";
    private static final String TOO_MANY_LINKS_MESSAGE = "The footer holds at most " + MAX_FOOTER_LINKS + " links";

    private HomepageSettingsValues() {
    }

    static HomepageLayout validated(HomepageLayout requested) {
        if (requested.footerLinks().size() > MAX_FOOTER_LINKS) {
            throw new ValidationException(TOO_MANY_LINKS_MESSAGE, TOO_MANY_LINKS_CODE);
        }
        List<FooterLink> links = requested.footerLinks().stream().map(HomepageSettingsValues::link).toList();
        return new HomepageLayout(
            line(requested.tagline(), 0, MAX_TAGLINE_LENGTH),
            address(requested.serverAddress()),
            requested.showPlayerCount(),
            links);
    }

    private static FooterLink link(FooterLink link) {
        return new FooterLink(line(link.label(), 1, MAX_LINK_LABEL_LENGTH), url(link.url()), link.kind());
    }

    private static String line(String value, int minLength, int maxLength) {
        String stripped = value.strip();
        int length = stripped.codePointCount(0, stripped.length());
        if (length < minLength || length > maxLength || stripped.chars().anyMatch(Character::isISOControl)) {
            throw new ValidationException(TEXT_INVALID_MESSAGE, TEXT_INVALID_CODE);
        }
        return stripped;
    }

    private static String address(String value) {
        String stripped = line(value, 0, MAX_SERVER_ADDRESS_LENGTH);
        if (stripped.chars().anyMatch(Character::isWhitespace)) {
            throw new ValidationException(TEXT_INVALID_MESSAGE, TEXT_INVALID_CODE);
        }
        return stripped;
    }

    private static String url(String value) {
        String stripped = value.strip();
        if (stripped.length() > MAX_LINK_URL_LENGTH || !SafeUrls.isAbsoluteWebUrl(stripped)) {
            throw new ValidationException(LINK_URL_INVALID_MESSAGE, LINK_URL_INVALID_CODE);
        }
        return stripped;
    }
}
