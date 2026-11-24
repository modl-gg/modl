package gg.modl.backend.infrastructure.onetimecode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HumanCodeTest {

    @Test
    void codesUseOnlyUnambiguousCharacters() {
        Set<String> codes = new HashSet<>();
        for (int index = 0; index < 200; index++) {
            String code = HumanCode.generate(8);
            assertThat(code).matches("[A-HJ-NP-Z2-9]{8}");
            codes.add(code);
        }
        assertThat(codes).hasSizeGreaterThan(190);
    }

    @Test
    void typedCodesAreNormalisedAndPrintedCodesAreGrouped() {
        assertThat(HumanCode.normalize(" abcd - efgh\t")).isEqualTo("ABCDEFGH");
        assertThat(HumanCode.format("abcdefgh")).isEqualTo("ABCD-EFGH");
        assertThat(HumanCode.format("ABCD-EFGH")).isEqualTo("ABCD-EFGH");
    }
}
