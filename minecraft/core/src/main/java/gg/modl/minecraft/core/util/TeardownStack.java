package gg.modl.minecraft.core.util;

import java.util.ArrayDeque;
import java.util.Deque;

public final class TeardownStack {
    private final PluginLogger logger;
    private final Deque<Step> steps = new ArrayDeque<>();

    public TeardownStack(PluginLogger logger) {
        this.logger = logger;
    }

    public synchronized void push(String description, Runnable undo) {
        steps.addFirst(new Step(description, undo));
    }

    public synchronized void pushFinal(String description, Runnable undo) {
        steps.addLast(new Step(description, undo));
    }

    public void unwind() {
        Step step;
        while ((step = next()) != null) {
            step.run(logger);
        }
    }

    private synchronized Step next() {
        return steps.pollFirst();
    }

    private static final class Step {
        private final String description;
        private final Runnable undo;

        private Step(String description, Runnable undo) {
            this.description = description;
            this.undo = undo;
        }

        private void run(PluginLogger logger) {
            try {
                undo.run();
            } catch (RuntimeException | LinkageError failure) {
                logger.warning("Could not " + description + " while shutting down modl", failure);
            }
        }
    }
}
