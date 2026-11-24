package gg.modl.backend.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.WritePause.PausedWrites;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WritePauseTest {
    private final WritePause pause = new WritePause();

    @Test
    void aPausedInstanceAdmitsOnlyTheWritesRunThroughItsPause() throws Exception {
        PausedWrites paused = pause.pauseAndDrain(Duration.ofSeconds(1)).orElseThrow();

        assertThat(CompletableFuture.supplyAsync(this::attempt).get(5, TimeUnit.SECONDS)).isEqualTo("DATABASE_MOVING");
        assertThat(attempt()).as("the pausing thread is not exempt outside its admitted writes").isEqualTo("DATABASE_MOVING");
        paused.admit(() -> assertThat(attempt()).isEmpty());
        assertThat(attempt()).isEqualTo("DATABASE_MOVING");

        paused.resume();
        assertThat(attempt()).isEmpty();
    }

    @Test
    void anOldPauseCannotEndALaterOne() {
        PausedWrites first = pause.pauseAndDrain(Duration.ofSeconds(1)).orElseThrow();
        first.resume();
        PausedWrites second = pause.pauseAndDrain(Duration.ofSeconds(1)).orElseThrow();

        first.resume();

        assertThat(attempt()).isEqualTo("DATABASE_MOVING");
        first.admit(() -> assertThat(attempt()).isEqualTo("DATABASE_MOVING"));
        second.resume();
    }

    @Test
    void pausingTwiceIsRefused() {
        PausedWrites paused = pause.pauseAndDrain(Duration.ofSeconds(1)).orElseThrow();

        assertThatThrownBy(() -> pause.pauseAndDrain(Duration.ofSeconds(1))).isInstanceOf(IllegalStateException.class);
        paused.resume();
    }

    @Test
    void pausingWaitsForTheWritesAlreadyRunning() throws Exception {
        pause.enter();
        CountDownLatch pausing = new CountDownLatch(1);
        CompletableFuture<Optional<PausedWrites>> drained = CompletableFuture.supplyAsync(() -> {
            pausing.countDown();
            return pause.pauseAndDrain(Duration.ofSeconds(10));
        });
        assertThat(pausing.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);

        assertThat(drained).isNotDone();
        pause.exit();

        drained.get(5, TimeUnit.SECONDS).orElseThrow().resume();
    }

    @Test
    void aWriteThatOutlastsTheBudgetCancelsThePause() {
        pause.enter();

        assertThat(pause.pauseAndDrain(Duration.ofMillis(50))).isEmpty();
        pause.exit();
        assertThat(attempt()).isEmpty();
    }

    private String attempt() {
        try {
            pause.enter();
            pause.exit();
            return "";
        } catch (DatabaseUnavailableException refused) {
            return refused.code();
        }
    }
}
