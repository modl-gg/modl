package gg.modl.backend.instance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ServerNamesTest {

    @Test
    void namesAreCutAtEightyCodePointsWithoutSplittingOne() {
        String emoji = "🎮";

        assertThat(ServerNames.normalize(" " + emoji.repeat(90) + " ")).contains(emoji.repeat(80));
    }

    @Test
    void aLongConfiguredNameIsTheSameOnEveryStart() {
        InstanceProperties properties = new InstanceProperties(null, null, "Block Party ".repeat(20), null, null, null);

        String name = properties.configuredServerName().orElseThrow();

        assertThat(name.length()).isLessThanOrEqualTo(80);
        assertThat(ServerNames.normalize(name)).contains(name);
    }

    @Test
    void blankNamesAreAbsent() {
        assertThat(ServerNames.normalize("  ")).isEmpty();
        assertThat(ServerNames.normalize(null)).isEmpty();
    }
}
