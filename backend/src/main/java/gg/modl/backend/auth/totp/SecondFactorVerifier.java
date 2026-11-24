package gg.modl.backend.auth.totp;

import gg.modl.backend.auth.repository.StaffTotp;
import gg.modl.backend.auth.repository.StaffTotpRepository;
import gg.modl.backend.database.UnitFrame;
import java.time.Clock;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SecondFactorVerifier {
    private static final Pattern TOTP_CODE = Pattern.compile("\\d{" + Totp.DIGITS + "}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s");

    private final StaffTotpRepository totpRepository;
    private final RecoveryCodes recoveryCodes;
    private final Clock clock;

    public Optional<SecondFactorMethod> verify(String email, String code) {
        UnitFrame.requireWrite();
        String compact = WHITESPACE.matcher(code).replaceAll("");
        if (TOTP_CODE.matcher(compact).matches()) {
            return verifyTotp(email, compact) ? Optional.of(SecondFactorMethod.TOTP) : Optional.empty();
        }
        if (RecoveryCodes.looksLikeRecoveryCode(compact) && totpRepository.isEnabled(email)
            && totpRepository.consumeRecoveryCode(email, recoveryCodes.hash(compact))) {
            return Optional.of(SecondFactorMethod.RECOVERY_CODE);
        }
        return Optional.empty();
    }

    public static boolean isTotpShaped(String code) {
        return TOTP_CODE.matcher(WHITESPACE.matcher(code).replaceAll("")).matches();
    }

    private boolean verifyTotp(String email, String code) {
        Optional<String> secret = totpRepository.find(email).filter(StaffTotp::enabled).map(StaffTotp::secret);
        if (secret.isEmpty()) {
            return false;
        }
        OptionalLong step = Totp.matchingStep(Base32.decode(secret.get()), code, clock.instant());
        return step.isPresent() && totpRepository.advanceStep(email, secret.get(), step.getAsLong());
    }
}
