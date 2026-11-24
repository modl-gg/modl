package gg.modl.backend.instance.setup;

public record PublicUrlCheck(PublicUrlVerdict verdict, String normalizedUrl) {

    static PublicUrlCheck invalid() {
        return new PublicUrlCheck(PublicUrlVerdict.INVALID, "");
    }
}
