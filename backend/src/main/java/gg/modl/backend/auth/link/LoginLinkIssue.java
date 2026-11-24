package gg.modl.backend.auth.link;

public sealed interface LoginLinkIssue {

    record Issued(String url, long expiresInSeconds) implements LoginLinkIssue {
    }

    record NotLinked() implements LoginLinkIssue {
    }

    record NotOnline() implements LoginLinkIssue {
    }
}
