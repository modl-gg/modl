package gg.modl.backend.auth.link;

public record LinkCodeIssue(String code, long expiresInSeconds, String command) {
}
