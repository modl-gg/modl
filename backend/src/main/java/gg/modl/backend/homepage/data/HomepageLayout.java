package gg.modl.backend.homepage.data;

import java.util.List;
import java.util.Objects;

public record HomepageLayout(String tagline, String serverAddress, boolean showPlayerCount, List<FooterLink> footerLinks,
                             long cardsRevision) {
    public static final HomepageLayout DEFAULT = new HomepageLayout("", "", false, List.of());

    public HomepageLayout {
        tagline = Objects.requireNonNullElse(tagline, "");
        serverAddress = Objects.requireNonNullElse(serverAddress, "");
        footerLinks = footerLinks == null ? List.of() : footerLinks.stream().filter(Objects::nonNull).toList();
    }

    public HomepageLayout(String tagline, String serverAddress, boolean showPlayerCount, List<FooterLink> footerLinks) {
        this(tagline, serverAddress, showPlayerCount, footerLinks, 0);
    }

    public HomepageLayout withCardsRevision(long revision) {
        return new HomepageLayout(tagline, serverAddress, showPlayerCount, footerLinks, revision);
    }
}
