package gg.modl.backend.auth.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.infrastructure.exception.ValidationException;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void lengthIsCountedInCodePointsBetweenTwelveAndOneHundredTwentyEight() {
        assertRejected("a".repeat(11), "PASSWORD_POLICY");
        assertThat(PasswordPolicy.requireAcceptable("a".repeat(12))).hasSize(12);
        assertThat(PasswordPolicy.requireAcceptable("a".repeat(128))).hasSize(128);
        assertRejected("a".repeat(129), "PASSWORD_POLICY");
    }

    @Test
    void astralCharactersCountOnceEach() {
        String twelveRockets = "🚀".repeat(12);

        assertThat(PasswordPolicy.requireAcceptable(twelveRockets)).isEqualTo(twelveRockets);
        assertRejected("🚀".repeat(11), "PASSWORD_POLICY");
    }

    @Test
    void compatibilityFormsNormalizeToTheSamePassword() {
        assertThat(PasswordPolicy.normalize("ａｂｃ")).isEqualTo("abc");
        assertThat(PasswordPolicy.normalize("é")).isEqualTo("é");
    }

    @Test
    void whitespaceIsKeptAndNoOtherRuleApplies() {
        assertThat(PasswordPolicy.requireAcceptable("  padded pass  ")).isEqualTo("  padded pass  ");
        assertThat(PasswordPolicy.requireAcceptable("passwordpassword")).isEqualTo("passwordpassword");
        assertThat(PasswordPolicy.requireAcceptable("owner@example.com")).isEqualTo("owner@example.com");
    }

    @Test
    void aLoneSurrogateIsAnEncodingError() {
        assertRejected("valid-prefix\uD83D", "INVALID_ARGUMENT");
        assertThatThrownBy(() -> PasswordPolicy.normalize("\uDE80")).isInstanceOf(ValidationException.class);
    }

    private static void assertRejected(String password, String code) {
        assertThatThrownBy(() -> PasswordPolicy.requireAcceptable(password))
            .isInstanceOfSatisfying(ValidationException.class, failure -> assertThat(failure.getMachineCode()).isEqualTo(code));
    }
}
