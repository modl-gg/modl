package gg.modl.minecraft.core.imports;

import lombok.Builder;
import lombok.Value;

import java.time.Duration;

@Value
@Builder(toBuilder = true)
class ImportTimings {
    static final ImportTimings DEFAULT = ImportTimings.builder()
            .reportEvery(Duration.ofSeconds(15))
            .pollEvery(Duration.ofSeconds(5))
            .retryEvery(Duration.ofSeconds(5))
            .giveUpAfter(Duration.ofMinutes(10))
            .endGiveUpAfter(Duration.ofSeconds(30))
            .announceEvery(Duration.ofSeconds(30))
            .build();

    Duration reportEvery, pollEvery, retryEvery, giveUpAfter, endGiveUpAfter, announceEvery;
}
