package gg.modl.backend.auth.signin;

import gg.modl.backend.auth.session.IssuedSession;

public sealed interface SignInOutcome {
    record Issued(IssuedSession session) implements SignInOutcome {
    }

    record Challenged(SecondFactorChallenge challenge) implements SignInOutcome {
    }
}
