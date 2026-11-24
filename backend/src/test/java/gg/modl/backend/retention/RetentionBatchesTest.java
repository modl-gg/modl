package gg.modl.backend.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetentionBatchesTest {
    @Test
    void drainStopsAtTheFirstShortBatch() {
        List<Integer> sizes = new ArrayList<>();

        long removed = RetentionBatches.drain(500, 10, size -> {
            sizes.add(size);
            return sizes.size() < 3 ? size : 7;
        });

        assertThat(removed).isEqualTo(1007);
        assertThat(sizes).containsExactly(500, 500, 500);
    }

    @Test
    void drainStopsAtTheBatchBudget() {
        assertThat(RetentionBatches.drain(500, 4, size -> size)).isEqualTo(2000);
    }

    @Test
    void drainAtMostAsksOnlyForWhatIsLeft() {
        List<Integer> sizes = new ArrayList<>();

        long removed = RetentionBatches.drainAtMost(1200, 500, 10, size -> {
            sizes.add(size);
            return size;
        });

        assertThat(removed).isEqualTo(1200);
        assertThat(sizes).containsExactly(500, 500, 200);
    }

    @Test
    void drainAtMostStopsWhenNothingMoreIsDeleted() {
        List<Integer> sizes = new ArrayList<>();

        long removed = RetentionBatches.drainAtMost(1200, 500, 10, size -> {
            sizes.add(size);
            return sizes.size() == 1 ? size : 0;
        });

        assertThat(removed).isEqualTo(500);
        assertThat(sizes).containsExactly(500, 500);
    }

    @Test
    void drainAtMostNeverExceedsTheBatchBudget() {
        assertThat(RetentionBatches.drainAtMost(1_000_000, 500, 3, size -> size)).isEqualTo(1500);
    }
}
