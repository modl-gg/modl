package gg.modl.minecraft.core.util;

import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeardownStackTest {
    private final RecordingPluginLogger logger = new RecordingPluginLogger();
    private final TeardownStack teardown = new TeardownStack(logger);
    private final List<String> undone = new ArrayList<>();

    @Test
    void undoesStepsInTheReverseOrderTheyWereStarted() {
        teardown.push("stop the first", () -> undone.add("first"));
        teardown.push("stop the second", () -> undone.add("second"));
        teardown.push("stop the third", () -> undone.add("third"));

        teardown.unwind();

        assertEquals(Arrays.asList("third", "second", "first"), undone);
    }

    @Test
    void finalStepsRunAfterEveryOtherStepInTheOrderTheyWereAdded() {
        teardown.push("stop the worker", () -> undone.add("worker"));
        teardown.pushFinal("uninstall the first registration", () -> undone.add("first registration"));
        teardown.push("stop the listener", () -> undone.add("listener"));
        teardown.pushFinal("uninstall the second registration", () -> undone.add("second registration"));

        teardown.unwind();

        assertEquals(Arrays.asList("listener", "worker", "first registration", "second registration"), undone);
    }

    @Test
    void aFailingStepIsLoggedAndTheRemainingStepsStillRun() {
        teardown.push("stop the first", () -> undone.add("first"));
        teardown.push("stop the broken one", () -> {
            throw new IllegalStateException("already closed");
        });

        teardown.unwind();

        assertEquals(Arrays.asList("first"), undone);
        assertTrue(logger.warnings().stream().anyMatch(line -> line.startsWith("Could not stop the broken one")));
    }

    @Test
    void unwindingTwiceUndoesEachStepOnce() {
        teardown.push("stop the only one", () -> undone.add("only"));

        teardown.unwind();
        teardown.unwind();

        assertEquals(Arrays.asList("only"), undone);
    }
}
