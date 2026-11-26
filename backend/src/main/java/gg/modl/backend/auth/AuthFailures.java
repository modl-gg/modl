package gg.modl.backend.auth;

import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ServiceUnavailableException;
import gg.modl.backend.infrastructure.exception.TooManyRequestsException;
import gg.modl.backend.infrastructure.exception.UnauthorizedException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import java.time.Duration;

public final class AuthFailures {
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String SIGN_IN_METHOD_DISABLED = "SIGN_IN_METHOD_DISABLED";
    public static final String TOO_MANY_ATTEMPTS = "TOO_MANY_ATTEMPTS";
    public static final String PASSWORD_HASHING_BUSY = "PASSWORD_HASHING_BUSY";
    public static final String SECOND_FACTOR_INVALID = "SECOND_FACTOR_INVALID";
    public static final String SIGN_IN_CHALLENGE_EXPIRED = "SIGN_IN_CHALLENGE_EXPIRED";
    public static final String RESET_LINK_INVALID = "RESET_LINK_INVALID";
    public static final String PASSWORD_POLICY = "PASSWORD_POLICY";
    public static final String PASSWORD_REQUIRED = "PASSWORD_REQUIRED";
    public static final String CURRENT_PASSWORD_INVALID = "CURRENT_PASSWORD_INVALID";
    public static final String REAUTHENTICATION_REQUIRED = "REAUTHENTICATION_REQUIRED";
    public static final String TOTP_ENROLLMENT_EXPIRED = "TOTP_ENROLLMENT_EXPIRED";
    public static final String TOTP_NOT_ENABLED = "TOTP_NOT_ENABLED";
    public static final String LAST_SECOND_FACTOR = "LAST_SECOND_FACTOR";
    public static final String OWNER_PASSWORD_REQUIRED = "OWNER_PASSWORD_REQUIRED";
    public static final String OWNER_SECOND_FACTOR_REQUIRED = "OWNER_SECOND_FACTOR_REQUIRED";
    public static final String OWNER_ACCOUNT_PROTECTED = "OWNER_ACCOUNT_PROTECTED";
    public static final String INVITATION_INVALID = "INVITATION_INVALID";
    public static final String SECOND_FACTOR_ENROLLMENT_REQUIRED = "SECOND_FACTOR_ENROLLMENT_REQUIRED";
    public static final String SECOND_FACTOR_REQUIRED = "SECOND_FACTOR_REQUIRED";

    private static final Duration HASHING_RETRY_AFTER = Duration.ofSeconds(2);

    private AuthFailures() {
    }

    public static UnauthorizedException invalidCredentials() {
        return new UnauthorizedException("The email or password is not correct", INVALID_CREDENTIALS);
    }

    public static ConflictException signInMethodDisabled() {
        return new ConflictException("This sign-in method is turned off on this server", SIGN_IN_METHOD_DISABLED);
    }

    public static TooManyRequestsException tooManyAttempts(Duration retryAfter) {
        return new TooManyRequestsException("Too many attempts; try again later or sign in another way", TOO_MANY_ATTEMPTS, retryAfter);
    }

    public static ServiceUnavailableException hashingBusy() {
        return new ServiceUnavailableException("The server is busy; try again in a few seconds", PASSWORD_HASHING_BUSY,
            HASHING_RETRY_AFTER);
    }

    public static ValidationException secondFactorInvalid() {
        return new ValidationException("The code is not correct or was already used", SECOND_FACTOR_INVALID);
    }

    public static ValidationException challengeExpired() {
        return new ValidationException("This sign-in expired; start again", SIGN_IN_CHALLENGE_EXPIRED);
    }

    public static ValidationException resetLinkInvalid() {
        return new ValidationException("This reset link is invalid, used, or expired", RESET_LINK_INVALID);
    }

    public static ValidationException passwordPolicy(String message) {
        return new ValidationException(message, PASSWORD_POLICY);
    }

    public static ValidationException passwordRequired() {
        return new ValidationException("A password is required", PASSWORD_REQUIRED);
    }

    public static ValidationException currentPasswordInvalid() {
        return new ValidationException("The current password is not correct", CURRENT_PASSWORD_INVALID);
    }

    public static ForbiddenException reauthenticationRequired() {
        return new ForbiddenException("Confirm it is you before changing this", REAUTHENTICATION_REQUIRED);
    }

    public static ValidationException totpEnrollmentExpired() {
        return new ValidationException("The authenticator setup expired; start again", TOTP_ENROLLMENT_EXPIRED);
    }

    public static ConflictException totpNotEnabled() {
        return new ConflictException("No authenticator app is set up", TOTP_NOT_ENABLED);
    }

    public static ConflictException lastSecondFactor() {
        return new ConflictException("Two-factor authentication is required; add another method first", LAST_SECOND_FACTOR);
    }

    public static ConflictException ownerPasswordRequired() {
        return new ConflictException("Set your own password before turning on password sign-in", OWNER_PASSWORD_REQUIRED);
    }

    public static ConflictException ownerSecondFactorRequired() {
        return new ConflictException("Add your own second factor and sign in with it before requiring two-factor authentication",
            OWNER_SECOND_FACTOR_REQUIRED);
    }

    public static ForbiddenException ownerAccountProtected() {
        return new ForbiddenException("The owner's credentials cannot be reset from the panel", OWNER_ACCOUNT_PROTECTED);
    }

    public static ValidationException invitationInvalid() {
        return new ValidationException("Invalid or expired invitation token.", INVITATION_INVALID);
    }

    public static ResourceNotFoundException staffNotFound() {
        return new ResourceNotFoundException("Staff member not found");
    }
}
