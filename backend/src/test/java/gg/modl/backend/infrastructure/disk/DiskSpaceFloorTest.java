package gg.modl.backend.infrastructure.disk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class DiskSpaceFloorTest {
    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void theFloorIsTheLargerOfTheFixedMinimumAndTheFractionOfTheDisk() throws IOException {
        DiskSpaceFloor floor = DiskSpaceFloor.DEFAULT;

        assertThat(floor.shortfall(new FakeFileStore(10 * GIB, GIB + 100), 100)).isZero();
        assertThat(floor.shortfall(new FakeFileStore(10 * GIB, GIB + 100), 150)).isEqualTo(50);
        assertThat(floor.shortfall(new FakeFileStore(100 * GIB, 5 * GIB + 100), 100)).isZero();
        assertThat(floor.shortfall(new FakeFileStore(100 * GIB, 5 * GIB), 100)).isEqualTo(100);
    }

    @Test
    void admitsExactlyWhenThereIsNoShortfall() throws IOException {
        DiskSpaceFloor floor = new DiskSpaceFloor(1_000, 0);

        assertThat(floor.admits(new FakeFileStore(10_000, 1_500), 500)).isTrue();
        assertThat(floor.admits(new FakeFileStore(10_000, 1_500), 501)).isFalse();
    }
}
