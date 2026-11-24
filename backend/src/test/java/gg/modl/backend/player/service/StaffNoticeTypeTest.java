package gg.modl.backend.player.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class StaffNoticeTypeTest {
    @Test
    void thePluginMapsExactlyTheseNoticeTypesToAnAudienceAndDropsAnyOther() {
        assertThat(Arrays.stream(StaffNoticeType.values()).map(Enum::name))
            .containsExactlyInAnyOrder("TICKET_CREATED", "PUNISHMENT_ISSUED", "PUNISHMENT_PARDONED");
    }
}
