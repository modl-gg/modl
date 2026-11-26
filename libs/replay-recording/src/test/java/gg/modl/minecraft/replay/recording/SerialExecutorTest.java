package gg.modl.minecraft.replay.recording;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SerialExecutorTest {
    @Test
    void aFailingTaskDoesNotStopTheTasksAfterIt() {
        List<String> ran = new ArrayList<>();
        List<RuntimeException> failures = new ArrayList<>();
        SerialExecutor executor = new SerialExecutor(Runnable::run, failures::add);
        executor.execute(() -> ran.add("first"));
        executor.execute(() -> {
            throw new IllegalStateException("boom");
        });
        executor.execute(() -> ran.add("third"));
        assertEquals(List.of("first", "third"), ran);
        assertEquals(1, failures.size());
    }
}
