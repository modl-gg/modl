package gg.modl.backend.homepage.data;

import java.util.Objects;

public record FooterLink(String label, String url, FooterLinkKind kind) {
    public FooterLink {
        label = Objects.requireNonNullElse(label, "");
        url = Objects.requireNonNullElse(url, "");
        kind = Objects.requireNonNullElse(kind, FooterLinkKind.OTHER);
    }
}
