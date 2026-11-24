package gg.modl.backend.update.compat;

import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import gg.modl.update.CompatibilityHeaders;
import gg.modl.update.VersionCompatibility;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class VersionMismatchException extends BaseApplicationException {
    private final VersionCompatibility compatibility;
    private final String serverVersion;

    public VersionMismatchException(VersionCompatibility compatibility, String serverVersion) {
        super("This modl server runs " + serverVersion + "; the plugin must run the same MAJOR.MINOR version", HttpStatus.CONFLICT,
            CompatibilityHeaders.VERSION_MISMATCH_CODE);
        this.compatibility = compatibility;
        this.serverVersion = serverVersion;
    }
}
