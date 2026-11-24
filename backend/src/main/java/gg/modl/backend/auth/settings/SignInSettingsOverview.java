package gg.modl.backend.auth.settings;

public record SignInSettingsOverview(SignInSettings settings, long version, int accountCount, int withoutPasswordCount,
                                     int withoutSecondFactorCount) {
}
