package gg.modl.backend.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FormFieldLabelsTest {

    @Test
    void aHeadingEndsInOneColonUnlessTheLabelAlreadyEndsInPunctuation() {
        assertThat(FormFieldLabels.heading("Steps to reproduce")).isEqualTo("Steps to reproduce:");
        assertThat(FormFieldLabels.heading("How can we help?")).isEqualTo("How can we help?");
        assertThat(FormFieldLabels.heading("Read the rules!")).isEqualTo("Read the rules!");
        assertThat(FormFieldLabels.heading("Your answer:")).isEqualTo("Your answer:");
        assertThat(FormFieldLabels.heading("Wie geht es? ")).isEqualTo("Wie geht es?");
        assertThat(FormFieldLabels.heading("你好？")).isEqualTo("你好？");
        assertThat(FormFieldLabels.heading("Name (optional)")).isEqualTo("Name (optional):");
    }

    @Test
    void anUnlabelledKeyIsTitleCased() {
        assertThat(FormFieldLabels.resolve("stepsToReproduce", null)).isEqualTo("Steps To Reproduce");
        assertThat(FormFieldLabels.resolve("field", Map.of("field", "How can we help?"))).isEqualTo("How can we help?");
        assertThat(FormFieldLabels.resolve("bugReport", Map.of("bugReport", " "))).isEqualTo("Bug Report");
    }
}
