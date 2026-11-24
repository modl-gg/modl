package gg.modl.backend.auth.controller;

import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.setOptionalTimestamp;
import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.toTimestamp;

import gg.modl.backend.auth.password.PasswordResetLink;
import gg.modl.backend.auth.repository.PasswordResetToken;
import gg.modl.backend.auth.settings.SignInMethod;
import gg.modl.backend.auth.settings.SignInSettingsOverview;
import gg.modl.backend.auth.signin.AccountSecurity;
import gg.modl.backend.auth.signin.CredentialProof;
import gg.modl.backend.auth.signin.SecondFactorChallenge;
import gg.modl.backend.auth.signin.SecondFactorState;
import gg.modl.backend.auth.staffsecurity.StaffSecurityEntry;
import gg.modl.backend.auth.totp.SecondFactorMethod;
import gg.modl.backend.auth.totp.TotpEnrollment;
import gg.modl.proto.modl.v1.AccountSecurityResponse;
import gg.modl.proto.modl.v1.InspectPasswordResetResponse;
import gg.modl.proto.modl.v1.PasswordResetLinkResponse;
import gg.modl.proto.modl.v1.PendingSecondFactor;
import gg.modl.proto.modl.v1.RecoveryCodesResponse;
import gg.modl.proto.modl.v1.SignInSettingsResponse;
import gg.modl.proto.modl.v1.StaffSecurityListResponse;
import gg.modl.proto.modl.v1.TotpEnrollmentResponse;
import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.Optional;

public final class SignInProtoMapper {
    private SignInProtoMapper() {
    }

    public static CredentialProof fromProto(gg.modl.proto.modl.v1.CredentialProof proof) {
        return new CredentialProof(proof.hasCurrentPassword() ? proof.getCurrentPassword() : null, proof.hasCode() ? proof.getCode() : null);
    }

    public static CredentialProof fromOptionalProof(gg.modl.proto.modl.v1.@Nullable CredentialProof proof) {
        return proof == null ? CredentialProof.NONE : fromProto(proof);
    }

    public static Optional<SignInMethod> fromProto(gg.modl.proto.modl.v1.SignInMethod method) {
        return switch (method) {
            case SIGN_IN_METHOD_EMAIL_CODE -> Optional.of(SignInMethod.EMAIL_CODE);
            case SIGN_IN_METHOD_PASSWORD -> Optional.of(SignInMethod.PASSWORD);
            case SIGN_IN_METHOD_UNSPECIFIED, UNRECOGNIZED -> Optional.empty();
        };
    }

    public static gg.modl.proto.modl.v1.SignInMethod toProto(SignInMethod method) {
        return switch (method) {
            case EMAIL_CODE -> gg.modl.proto.modl.v1.SignInMethod.SIGN_IN_METHOD_EMAIL_CODE;
            case PASSWORD -> gg.modl.proto.modl.v1.SignInMethod.SIGN_IN_METHOD_PASSWORD;
        };
    }

    public static gg.modl.proto.modl.v1.SecondFactorState toProto(SecondFactorState state) {
        return switch (state) {
            case SATISFIED -> gg.modl.proto.modl.v1.SecondFactorState.SECOND_FACTOR_STATE_SATISFIED;
            case ENROLLMENT_REQUIRED -> gg.modl.proto.modl.v1.SecondFactorState.SECOND_FACTOR_STATE_ENROLLMENT_REQUIRED;
            case VERIFICATION_REQUIRED -> gg.modl.proto.modl.v1.SecondFactorState.SECOND_FACTOR_STATE_VERIFICATION_REQUIRED;
        };
    }

    static gg.modl.proto.modl.v1.SecondFactorMethod toProto(SecondFactorMethod method) {
        return switch (method) {
            case TOTP -> gg.modl.proto.modl.v1.SecondFactorMethod.SECOND_FACTOR_METHOD_TOTP;
            case RECOVERY_CODE -> gg.modl.proto.modl.v1.SecondFactorMethod.SECOND_FACTOR_METHOD_RECOVERY_CODE;
            case PASSKEY -> gg.modl.proto.modl.v1.SecondFactorMethod.SECOND_FACTOR_METHOD_PASSKEY;
        };
    }

    static PendingSecondFactor toProto(SecondFactorChallenge challenge) {
        PendingSecondFactor.Builder pending = PendingSecondFactor.newBuilder()
            .setChallenge(challenge.token())
            .setExpiresInSeconds(challenge.expiresInSeconds())
            .setEmail(challenge.email());
        challenge.methods().forEach(method -> pending.addMethods(toProto(method)));
        return pending.build();
    }

    static AccountSecurityResponse toProto(AccountSecurity security) {
        AccountSecurityResponse.Builder response = AccountSecurityResponse.newBuilder()
            .setMethod(toProto(security.method()))
            .setHasPassword(security.hasPassword())
            .setTotpEnabled(security.totpEnabled())
            .setRecoveryCodesRemaining(security.recoveryCodesRemaining())
            .setPasskeyCount(security.passkeyCount())
            .setSecondFactorRequired(security.secondFactorRequired())
            .setSessionVerified(security.sessionVerified())
            .setState(toProto(security.state()));
        setOptionalTimestamp(response::setTotpEnabledAt, security.totpEnabledAt());
        return response.build();
    }

    static TotpEnrollmentResponse toProto(TotpEnrollment enrollment) {
        return TotpEnrollmentResponse.newBuilder()
            .setSecret(enrollment.secret())
            .setKeyUri(enrollment.keyUri())
            .setExpiresInSeconds(enrollment.expiresInSeconds())
            .build();
    }

    static RecoveryCodesResponse toRecoveryCodes(List<String> codes) {
        return RecoveryCodesResponse.newBuilder().addAllCodes(codes).build();
    }

    static InspectPasswordResetResponse toProto(PasswordResetToken token) {
        return InspectPasswordResetResponse.newBuilder()
            .setEmail(token.email())
            .setExpiresAt(toTimestamp(token.expiresAt()))
            .build();
    }

    public static SignInSettingsResponse toProto(SignInSettingsOverview overview) {
        return SignInSettingsResponse.newBuilder()
            .setMethod(toProto(overview.settings().method()))
            .setSecondFactorRequired(overview.settings().secondFactorRequired())
            .setVersion(overview.version())
            .setAccountCount(overview.accountCount())
            .setWithoutPasswordCount(overview.withoutPasswordCount())
            .setWithoutSecondFactorCount(overview.withoutSecondFactorCount())
            .build();
    }

    static StaffSecurityListResponse toStaffSecurity(List<StaffSecurityEntry> entries) {
        StaffSecurityListResponse.Builder response = StaffSecurityListResponse.newBuilder();
        for (StaffSecurityEntry entry : entries) {
            gg.modl.proto.modl.v1.StaffSecurityEntry.Builder row = gg.modl.proto.modl.v1.StaffSecurityEntry.newBuilder()
                .setEmail(entry.email())
                .setHasPassword(entry.hasPassword())
                .setTotpEnabled(entry.totpEnabled())
                .setHasPasskey(entry.hasPasskey())
                .setOwner(entry.owner());
            if (entry.staffId() != null) {
                row.setStaffId(entry.staffId());
            }
            response.addEntries(row);
        }
        return response.build();
    }

    static PasswordResetLinkResponse toProto(PasswordResetLink link) {
        return PasswordResetLinkResponse.newBuilder()
            .setUrl(link.url())
            .setEmail(link.email())
            .setExpiresAt(toTimestamp(link.expiresAt()))
            .build();
    }
}
