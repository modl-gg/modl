package gg.modl.backend.instance.setup;

import gg.modl.backend.email.ValidityPeriod;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.onetimecode.HumanCode;
import gg.modl.backend.instance.InstanceProperties;
import gg.modl.backend.instance.PublicUrls;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class SetupCodeAnnouncer {
    private static final DateTimeFormatter SERVER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final PublicUrls publicUrls;
    private final InstanceProperties instanceProperties;
    private final SetupCodeFile setupCodeFile;
    private final OperatorNotice operatorNotice;

    void announce(SetupCodeKind kind, String code, IssuedSetupCode issued) {
        String displayCode = HumanCode.format(code);
        String setupPage = setupPage();
        Optional<String> savedAt = setupCodeFile.write(fileContent(kind, displayCode, setupPage, issued.expiresAt()));
        String validity = new ValidityPeriod(Duration.between(issued.issuedAt(), issued.expiresAt())).describe();
        operatorNotice.publish("{}: {}. Open {} and enter it (valid {}).", kind.consoleLabel(), displayCode, setupPage, validity);
        recoveryHint(kind, savedAt).ifPresent(hint -> operatorNotice.publish("{}", hint));
    }

    private String setupPage() {
        return publicUrls.knownOrigin().isPresent() ? publicUrls.setupPage() : publicUrls.hostNeutralSetupPage();
    }

    private Optional<String> recoveryHint(SetupCodeKind kind, Optional<String> savedAt) {
        Optional<String> fileSentence = savedAt.map(path -> "The code is also saved in " + path + ".");
        if (!kind.offersConfigFallback()) {
            return fileSentence;
        }
        String fallback = "stop the server, " + SetupHost.of(instanceProperties).adminEmailInstruction() + " and start it again.";
        return Optional.of(fileSentence
            .map(sentence -> sentence + " If this console is unreliable, read it there, or " + fallback)
            .orElse("If this console is unreliable, " + fallback));
    }

    private static String fileContent(SetupCodeKind kind, String displayCode, String setupPage, Instant expiresAt) {
        return "modl " + kind.fileLabel() + ": " + displayCode + System.lineSeparator()
            + "Open " + setupPage + " and enter the code." + System.lineSeparator()
            + "Valid until " + SERVER_TIME.format(expiresAt) + " (server time). This file is deleted " + kind.fileDeletedWhen() + "."
            + System.lineSeparator()
            + "For a new code, restart the server." + System.lineSeparator();
    }
}
