package gg.modl.backend.theme.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class CustomCssPolicyTest {

    static Stream<Arguments> rejectedStylesheets() {
        return Stream.of(
            Arguments.of("@import url(https://example.com/x.css);", CustomCssViolation.IMPORT),
            Arguments.of("@IMPORT 'local.css';", CustomCssViolation.IMPORT),
            Arguments.of("@\\69mport url(x.css);", CustomCssViolation.IMPORT),
            Arguments.of("@\\000069 mport url(x.css);", CustomCssViolation.IMPORT),
            Arguments.of("@im\\port url(x.css);", CustomCssViolation.IMPORT),
            Arguments.of("@import/**/url(x.css);", CustomCssViolation.IMPORT),
            Arguments.of("@x \"/*\"; @import url(/a.css); a{b:\"*/\"}", CustomCssViolation.IMPORT),
            Arguments.of("a{content:'</style><script>'}", CustomCssViolation.MARKUP),
            Arguments.of("a{content:'<\\/STYLE>'}", CustomCssViolation.MARKUP),
            Arguments.of("a{background:url(javascript:alert(1))}", CustomCssViolation.SCRIPT),
            Arguments.of("a{background:url('java\tscript:alert(1)')}", CustomCssViolation.SCRIPT),
            Arguments.of("a{background:url(\\6a avascript:alert(1))}", CustomCssViolation.SCRIPT),
            Arguments.of("a{background:url(vbscript:x)}", CustomCssViolation.SCRIPT),
            Arguments.of("a{width:expression(alert(1))}", CustomCssViolation.SCRIPT),
            Arguments.of("a{width:EXPRESSION /**/ (alert(1))}", CustomCssViolation.SCRIPT),
            Arguments.of("a{behavior:url(x.htc)}", CustomCssViolation.SCRIPT),
            Arguments.of("a{-moz-binding:url(x.xml#y)}", CustomCssViolation.SCRIPT),
            Arguments.of("a{color:red}\u0000", CustomCssViolation.INVALID_CHARACTER),
            Arguments.of("a{content:'" + "x".repeat(CustomCssPolicy.MAX_BYTES) + "'}", CustomCssViolation.TOO_LARGE));
    }

    @ParameterizedTest
    @MethodSource("rejectedStylesheets")
    void dangerousOrOversizedStylesheetsAreRejectedWithTheirCode(String css, CustomCssViolation expected) {
        assertThat(CustomCssPolicy.violation(css)).contains(expected);
        assertThatThrownBy(() -> CustomCssPolicy.accepted(css))
            .isInstanceOfSatisfying(BaseApplicationException.class, error -> assertThat(error.getMachineCode()).isEqualTo(expected.code()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "[data-modl=\"card\"] { border-radius: 0; scroll-behavior: smooth; overscroll-behavior: contain; }",
        "@media (max-width: 600px) { [data-modl=\"hero\"] { padding: 0 } }",
        "@font-face { font-family: X; src: url(data:font/woff2;base64,AAAA) }",
        "[data-modl=\"page\"] { background: url(/v1/storage/objects/modl_instance/theme_background/a.png) }",
        "a::after { content: '\\2014 \\201C' }"
    })
    void ordinaryStylesheetsAreAccepted(String css) {
        assertThat(CustomCssPolicy.violation(css)).isEmpty();
        assertThat(CustomCssPolicy.accepted(css).css()).isEqualTo(css);
    }

    @Test
    void theLimitCountsUtf8Bytes() {
        String twoByteCharacters = "é".repeat(CustomCssPolicy.MAX_BYTES / 2);

        assertThat(CustomCssPolicy.violation(twoByteCharacters)).isEmpty();
        assertThat(CustomCssPolicy.violation(twoByteCharacters + "a")).contains(CustomCssViolation.TOO_LARGE);
    }
}
