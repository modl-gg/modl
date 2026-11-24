package gg.modl.backend.migration.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.infrastructure.exception.ValidationException;
import org.junit.jupiter.api.Test;

class ImportOriginTest {
    @Test
    void controlAndColourCharactersAreRemovedFromTheReportedName() {
        ImportOrigin origin = ImportOrigin.reportedBy(" proxy\n\u00a7c\u0000one\u202e ", "192.0.2.4");

        assertThat(origin.serverName()).isEqualTo("proxycone");
        assertThat(origin.address()).isEqualTo("192.0.2.4");
    }

    @Test
    void theNameIsCappedInCodePoints() {
        ImportOrigin origin = ImportOrigin.reportedBy("\ud83c\udfae".repeat(80), null);

        assertThat(origin.serverName().codePointCount(0, origin.serverName().length()))
            .isEqualTo(ImportOrigin.MAX_SERVER_NAME_LENGTH);
    }

    @Test
    void aNameWithNothingPrintableIsRefused() {
        assertThatThrownBy(() -> ImportOrigin.reportedBy("\n\t ", null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ImportOrigin.reportedBy(null, null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void anAddressThatCannotBeStoredIsNotRecorded() {
        assertThat(ImportOrigin.reportedBy("proxy", "f".repeat(46)).address()).isNull();
    }
}
